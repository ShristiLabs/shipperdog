package com.shipperdog.api.release.internal.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.release.ReleaseException;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.release.internal.entity.ReleaseArtifactEntity;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleaseManifestEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetGroupEntity;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestDocument.Approval;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestDocument.Artifact;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestDocument.Configuration;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestDocument.Release;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestDocument.Ring;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestDocument.Rollout;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestDocument.Target;
import com.shipperdog.api.release.internal.repository.ReleaseArtifactRepository;
import com.shipperdog.api.release.internal.repository.ReleaseManifestRepository;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetGroupRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetRepository;
import com.shipperdog.api.release.internal.repository.RingRepository;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The frozen manifest of PRD 16 (issue #57, S9-03, FR-024): the
 * complete, immutable, reproducible record of WHAT a release ships,
 * assembled by deep copy at the APPROVED crossing and served from
 * storage forever after — never recomputed from live rows (AC3). The
 * manifest is the compliance answer to PRD 16's question ("exactly
 * what did Acme receive on October 4?"): artifacts pinned by content
 * (sha256, build, size, filename), the full target set with tester
 * groups, the ring, the environment, the rollout, the required
 * approvals, and the configuration snapshot — every field a VALUE
 * copied at freeze, never a lazy reference to a mutable row.
 *
 * <p><strong>Freeze</strong> (AC1) rides the S9-02 seam exactly as
 * designed: {@link ReleaseManifestFreezeListener} observes
 * {@link ReleaseTransitionedEvent} synchronously, inside the
 * transition's committing transaction and bound scope, so the manifest
 * row and the APPROVED crossing commit or roll back together — no
 * release is ever APPROVED without its manifest, and no manifest
 * exists without a committed APPROVED crossing. Immutability is
 * structural: V26's one-per-release unique constraint, a repository
 * with insert and read operations only, an entity without setters, and
 * a forward-only state machine that cannot re-enter APPROVED. A
 * manifest is never mutated to match a drifted world (INV-003/004
 * discipline): reads verify the stored content hash and fail loudly
 * (AC-listed drift detection) — the platform repairs nothing silently.
 *
 * <p><strong>Canonicalization.</strong> The manifest's identity is the
 * SHA-256 over a canonical serialization of the document: fields
 * sorted, ISO-8601 instants, nulls included. The digest is computed
 * over those bytes at freeze, stored beside the document, recorded in
 * the freeze audit event (AC4), and RE-derived on every read from the
 * parsed content — PostgreSQL's jsonb storage re-orders keys, so
 * byte-stability lives in this code, and any storage corruption or
 * tampering surfaces as {@code RELEASE_MANIFEST_CORRUPT} (500), never
 * as a quietly served wrong document. The same canonical bytes render
 * as YAML on request (AC3): one stored document, two serializations.
 *
 * <p><strong>Reads</strong> follow the module's standing shape: the
 * application resolves as the permission target's registry profile,
 * the service establishes the owning scope through the sanctioned
 * executor, the release resolves scoped with the concealed miss, and
 * the manifest is the release's plain child (the V25 doctrine — a
 * release of another application is the standard concealed
 * {@code RELEASE_NOT_FOUND}, byte-identical to an unknown id). A
 * release that has not yet crossed into APPROVED answers the honest
 * state miss {@code RELEASE_MANIFEST_NOT_FOUND}: the release is
 * readable, the manifest simply does not exist yet.
 *
 * <p><strong>Diff</strong> (AC5) compares two STORED documents — two
 * frozen moments of the same application's history, never live state:
 * per-platform artifact pins added, removed, or changed; target
 * tenants and their group selections added, removed, or changed; and
 * every scalar block (ring, environment, rollout) and configuration
 * key that moved between the two freezes.
 */
@Service
public class ReleaseManifestService {

    /**
     * The canonical serialization: the document as a JSON tree with
     * object keys sorted recursively (see {@link #canonicalized}),
     * ISO-8601 instants (Jackson 3's built-in java.time default),
     * nulls included, unknown fields tolerated on read. This ONE byte
     * form is the manifest's digest definition, derived identically at
     * freeze (record to tree, then sorted) and at verification (stored
     * jsonb text to tree, then sorted) so PostgreSQL's key reordering
     * never reaches the hash.
     */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * The YAML render of a stored document (AC3): Jackson 2's YAML
     * dataformat — already the platform's classpath through the
     * OpenAPI starter, which serves {@code /v3/api-docs.yaml} through
     * the same mapper — over the canonical JSON, so the YAML answer is
     * the stored document in a second serialization, never a second
     * source of truth.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper YAML =
            new com.fasterxml.jackson.databind.ObjectMapper(new YAMLFactory());

    private final ReleaseManifestRepository manifests;
    private final ReleaseScopedRepository releases;
    private final ReleaseArtifactRepository releaseArtifacts;
    private final ReleaseTargetRepository targets;
    private final ReleaseTargetGroupRepository targetGroups;
    private final RingRepository rings;
    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final ArtifactAccess artifacts;
    private final TenantScopeExecutor scopeExecutor;
    private final AuditEventPublisher audit;

    ReleaseManifestService(
            ReleaseManifestRepository manifests,
            ReleaseScopedRepository releases,
            ReleaseArtifactRepository releaseArtifacts,
            ReleaseTargetRepository targets,
            ReleaseTargetGroupRepository targetGroups,
            RingRepository rings,
            ApplicationAccess applications,
            VersionAccess versions,
            ArtifactAccess artifacts,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit) {
        this.manifests = manifests;
        this.releases = releases;
        this.releaseArtifacts = releaseArtifacts;
        this.targets = targets;
        this.targetGroups = targetGroups;
        this.rings = rings;
        this.applications = applications;
        this.versions = versions;
        this.artifacts = artifacts;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
    }

    // -------------------------------------------------------------------------
    // Freeze (AC1, AC2, AC4)
    // -------------------------------------------------------------------------

    /**
     * Freezes the release's manifest at the APPROVED crossing (AC1):
     * deep-copies every fact the release ships into the PRD 16
     * document, hashes the canonical bytes, writes the one row the
     * release will ever carry, and audits the digest (AC4). Runs
     * inside the transition's transaction and bound scope (the
     * listener's contract): an assembly failure rolls the crossing
     * back — fail loudly, never an APPROVED release without its
     * record. A second freeze is impossible three ways (the unique
     * constraint, the read-only-after-first shape, and PRD 17's
     * forward-only graph); the explicit check answers the precise 409
     * should any future surface ever try.
     */
    @Transactional
    public void freeze(ReleaseTransitionedEvent event) {
        if (manifests.findByReleaseId(event.releaseId()).isPresent()) {
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_MANIFEST_IMMUTABLE, "the release manifest is already frozen");
        }
        ReleaseEntity release = releases.findScoped(event.releaseId())
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found"));
        ReleaseManifestDocument document = assemble(release);
        byte[] canonical = CANONICAL.writeValueAsBytes(canonicalized(CANONICAL.valueToTree(document)));
        String digest = sha256Hex(canonical);
        manifests.save(new ReleaseManifestEntity(
                release.getId(),
                new String(canonical, StandardCharsets.UTF_8),
                digest,
                event.occurredAt(),
                event.actorId()));

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseRef", event.releaseRef());
        detail.put("sha256", digest);
        detail.put("artifacts", String.valueOf(document.artifacts().size()));
        detail.put("targets", String.valueOf(document.targets().size()));
        detail.put("ring", document.ring() == null ? "-" : document.ring().code());
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_MANIFEST_FROZEN)
                .occurredAt(event.occurredAt())
                .userId(event.actorId())
                .orgId(event.organizationId())
                .tenantId(event.tenantId())
                .applicationId(event.applicationId())
                .versionId(event.versionId())
                .success(true)
                .detail(detail)
                .build());
    }

    /**
     * The deep copy itself (AC2, the issue's technical note): every
     * block resolved and copied as VALUES at this instant — the
     * application and version names as frozen text, the artifacts
     * pinned by content (INV-003 froze those fields at upload; this
     * read is their first and only consumer in the release flow), the
     * targets with their group selections, and the ring's frozen
     * code/name/position. The environment, rollout percentage, and
     * configuration values are null until their owning stories land
     * (S9-04 environments and versioned config, E10 staged rollouts,
     * S10-01 approval policies): the keys are present — the document's
     * shape is complete from day one — and the nulls are the honest
     * record of what the platform modeled at this freeze.
     */
    private ReleaseManifestDocument assemble(ReleaseEntity release) {
        List<ReleaseArtifactEntity> artifactRows =
                releaseArtifacts.findByReleaseIdOrderByPlatformAscArtifactIdAsc(release.getId());
        Set<UUID> artifactIds = new LinkedHashSet<>();
        artifactRows.forEach(row -> artifactIds.add(row.getArtifactId()));
        Map<UUID, ArtifactAccess.ArtifactPin> pins = artifacts.pinsOf(artifactIds);
        if (pins.size() != artifactIds.size()) {
            throw new IllegalStateException(
                    "artifact pin missing at manifest freeze for release " + release.releaseRef());
        }
        List<Artifact> artifactPins = new ArrayList<>(artifactRows.size());
        for (ReleaseArtifactEntity row : artifactRows) {
            ArtifactAccess.ArtifactPin pin = pins.get(row.getArtifactId());
            artifactPins.add(new Artifact(
                    row.getPlatform(),
                    pin.artifactId(),
                    pin.fileName(),
                    pin.buildNumber(),
                    pin.sha256(),
                    pin.sizeBytes(),
                    pin.uploadedAt()));
        }

        Map<UUID, List<UUID>> groupsByTenant = new LinkedHashMap<>();
        for (ReleaseTargetGroupEntity group :
                targetGroups.findByReleaseIdOrderByTenantIdAscGroupIdAsc(release.getId())) {
            groupsByTenant
                    .computeIfAbsent(group.getTenantId(), tenant -> new ArrayList<>())
                    .add(group.getGroupId());
        }
        List<Target> targetPins = new ArrayList<>();
        for (ReleaseTargetEntity row : targets.findByReleaseIdOrderByTenantIdAsc(release.getId())) {
            targetPins.add(new Target(
                    row.getTenantId(), List.copyOf(groupsByTenant.getOrDefault(row.getTenantId(), List.of()))));
        }

        RingEntity ring = rings.findById(release.getStartingRingId())
                .orElseThrow(() -> new IllegalStateException(
                        "starting ring missing at manifest freeze for release " + release.releaseRef()));
        String applicationName = applications
                .summaryOf(release.getApplicationId())
                .map(ApplicationAccess.ApplicationSummary::name)
                .orElseThrow(() -> new IllegalStateException(
                        "application summary missing at manifest freeze for release " + release.releaseRef()));
        String versionName = versions.recordOf(release.getVersionId())
                .map(VersionAccess.VersionRecord::versionName)
                .orElseThrow(() -> new IllegalStateException(
                        "version record missing at manifest freeze for release " + release.releaseRef()));

        return new ReleaseManifestDocument(
                new Release(
                        release.getId(),
                        release.releaseRef(),
                        release.getApplicationId(),
                        applicationName,
                        release.getVersionId(),
                        versionName,
                        release.getOrganizationId()),
                List.copyOf(artifactPins),
                List.copyOf(targetPins),
                new Ring(ring.getCode(), ring.getName(), ring.getPosition()),
                null,
                new Rollout(null),
                new Configuration(null, null, null),
                new Approval(List.of()));
    }

    // -------------------------------------------------------------------------
    // Reads: the stored manifest (AC3) and the diff between two (AC5)
    // -------------------------------------------------------------------------

    /**
     * The stored manifest (AC3): the frozen document read back from
     * storage — never recomputed from live rows, so a later
     * application rename, ring vocabulary change, or any other world
     * drift can never rewrite history — verified against its stored
     * digest before it is served. YAML is the same stored document in
     * its second serialization ({@link #yaml}).
     */
    @Transactional(readOnly = true)
    public ManifestView manifest(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            return verifiedView(manifests
                    .findByReleaseId(release.getId())
                    .orElseThrow(() -> new ReleaseException(
                            ReleaseException.Code.RELEASE_MANIFEST_NOT_FOUND,
                            "no manifest is frozen for this release")));
        });
    }

    /**
     * The diff between two releases' frozen manifests (AC5): what
     * moved between the two APPROVED freezes — artifact pins per
     * platform, the target set with its group selections, and every
     * scalar or configuration block that differs. Both releases must
     * belong to the addressed application (a foreign one conceals as
     * the standard miss) and both must have crossed into APPROVED (a
     * manifest-less side is the honest state miss). Computed over the
     * STORED documents only: the diff is a statement about two frozen
     * moments, never about live state.
     */
    @Transactional(readOnly = true)
    public ManifestDiffView diff(UUID actorId, UUID applicationId, UUID releaseId, UUID otherReleaseId) {
        if (releaseId.equals(otherReleaseId)) {
            throw invalid(Map.of("otherReleaseId", "a diff needs two distinct releases"));
        }
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity fromRelease = scopedReleaseOf(applicationId, releaseId);
            ReleaseEntity toRelease = scopedReleaseOf(applicationId, otherReleaseId);
            ManifestView from = verifiedView(manifests
                    .findByReleaseId(fromRelease.getId())
                    .orElseThrow(() -> new ReleaseException(
                            ReleaseException.Code.RELEASE_MANIFEST_NOT_FOUND,
                            "no manifest is frozen for this release")));
            ManifestView to = verifiedView(manifests
                    .findByReleaseId(toRelease.getId())
                    .orElseThrow(() -> new ReleaseException(
                            ReleaseException.Code.RELEASE_MANIFEST_NOT_FOUND,
                            "no manifest is frozen for this release")));
            return diffOf(from, to);
        });
    }

    /** The canonical JSON of a served view — the YAML render's input, byte-stable across reads. */
    public String canonicalJson(ManifestView view) {
        return CANONICAL.writeValueAsString(view);
    }

    /**
     * The YAML render (AC3): the canonical JSON through the YAML
     * dataformat — the same stored document, second serialization. The
     * YAML document and the JSON answer carry identical content; the
     * digest pins both.
     */
    public String yaml(ManifestView view) {
        try {
            return YAML.writeValueAsString(YAML.readTree(canonicalJson(view)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot render the manifest as YAML", e);
        }
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /**
     * The served manifest (AC3): the storage metadata — the digest
     * (identical to the freeze audit's, AC4), the frozen moment, the
     * actor who crossed into APPROVED — beside the frozen document
     * itself.
     */
    public record ManifestView(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            String sha256,
            Instant frozenAt,
            UUID frozenBy,
            ReleaseManifestDocument manifest) {}

    /** One side of the diff: the release identity beside its manifest's freeze facts. */
    public record DiffEndpoint(UUID releaseId, String releaseRef, String sha256, Instant frozenAt) {}

    /** A scalar block that moved between the two freezes: the frozen values, rendered as text. */
    public record FieldChange(String from, String to) {}

    /** The artifact pin as the diff shows it: content identity and build facts. */
    public record ArtifactPinView(Long buildNumber, String sha256, long sizeBytes, String fileName) {}

    /**
     * One platform's artifact movement: ADDED (no pin in the from
     * freeze), REMOVED (no pin in the to freeze), or CHANGED (both
     * pins present, differing in any fact).
     */
    public record ArtifactChange(String change, ArtifactPinView from, ArtifactPinView to) {}

    /** One tenant's tester-group movement between the two freezes. */
    public record GroupChange(List<UUID> added, List<UUID> removed) {}

    /** The target-set movement: tenants added and removed, and per-tenant group changes. */
    public record TargetChanges(List<UUID> added, List<UUID> removed, Map<UUID, GroupChange> groupsChanged) {}

    /**
     * The diff of two frozen manifests (AC5): artifact movements keyed
     * by platform, the target-set movements, the scalar blocks that
     * moved (null when identical), and the configuration keys that
     * differ. Deterministic ordering throughout (platforms and tenants
     * sorted; only differences appear).
     */
    public record ManifestDiffView(
            DiffEndpoint from,
            DiffEndpoint to,
            Map<String, ArtifactChange> artifacts,
            TargetChanges targets,
            FieldChange ring,
            FieldChange environment,
            FieldChange rollout,
            List<String> configurationChanges) {}

    // -------------------------------------------------------------------------
    // Verification and diff computation
    // -------------------------------------------------------------------------

    /**
     * The read-side integrity gate: parses the stored content and
     * re-derives the canonical digest — any mismatch with the stored
     * hash (storage corruption, tampering) answers
     * {@code RELEASE_MANIFEST_CORRUPT} loudly; the platform never
     * serves, repairs, or rewrites a manifest that fails verification
     * (the never-mutate discipline — an operator investigates).
     */
    private ManifestView verifiedView(ReleaseManifestEntity row) {
        JsonNode tree = CANONICAL.readTree(row.getContent());
        String digest = sha256Hex(CANONICAL.writeValueAsBytes(canonicalized(tree)));
        if (!digest.equals(row.getSha256())) {
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_MANIFEST_CORRUPT,
                    "the stored release manifest failed integrity verification");
        }
        ReleaseManifestDocument document = CANONICAL.treeToValue(tree, ReleaseManifestDocument.class);
        return new ManifestView(
                row.getReleaseId(),
                document.release().releaseRef(),
                document.release().applicationId(),
                document.release().versionId(),
                row.getSha256(),
                row.getFrozenAt(),
                row.getFrozenBy(),
                document);
    }

    /** The recursive key-sorting pass that makes a parsed tree serialize to canonical bytes again. */
    private static JsonNode canonicalized(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sortedFields = new TreeMap<>();
            node.properties().forEach(entry -> sortedFields.put(entry.getKey(), entry.getValue()));
            ObjectNode sorted = CANONICAL.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : sortedFields.entrySet()) {
                sorted.set(entry.getKey(), canonicalized(entry.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(child -> elements.add(canonicalized(child)));
            return CANONICAL.createArrayNode().addAll(elements);
        }
        return node;
    }

    /** The diff of two verified views: only the movements appear, deterministic order throughout. */
    private static ManifestDiffView diffOf(ManifestView from, ManifestView to) {
        Map<String, Artifact> fromArtifacts = new TreeMap<>();
        from.manifest().artifacts().forEach(artifact -> fromArtifacts.put(artifact.platform(), artifact));
        Map<String, Artifact> toArtifacts = new TreeMap<>();
        to.manifest().artifacts().forEach(artifact -> toArtifacts.put(artifact.platform(), artifact));
        Map<String, ArtifactChange> artifactChanges = new LinkedHashMap<>();
        Set<String> platforms = new LinkedHashSet<>();
        platforms.addAll(fromArtifacts.keySet());
        platforms.addAll(toArtifacts.keySet());
        for (String platform : platforms) {
            Artifact before = fromArtifacts.get(platform);
            Artifact after = toArtifacts.get(platform);
            if (before == null) {
                artifactChanges.put(platform, new ArtifactChange("ADDED", null, pinOf(after)));
            } else if (after == null) {
                artifactChanges.put(platform, new ArtifactChange("REMOVED", pinOf(before), null));
            } else if (!Objects.equals(before, after)) {
                artifactChanges.put(platform, new ArtifactChange("CHANGED", pinOf(before), pinOf(after)));
            }
        }

        Map<UUID, List<UUID>> fromGroups = groupsByTenant(from.manifest().targets());
        Map<UUID, List<UUID>> toGroups = groupsByTenant(to.manifest().targets());
        List<UUID> added = new ArrayList<>();
        List<UUID> removed = new ArrayList<>();
        Map<UUID, GroupChange> groupsChanged = new LinkedHashMap<>();
        Set<UUID> tenants = new LinkedHashSet<>();
        tenants.addAll(fromGroups.keySet());
        tenants.addAll(toGroups.keySet());
        for (UUID tenant : new java.util.TreeSet<>(tenants)) {
            List<UUID> before = fromGroups.get(tenant);
            List<UUID> after = toGroups.get(tenant);
            if (before == null) {
                added.add(tenant);
            } else if (after == null) {
                removed.add(tenant);
            } else if (!Objects.equals(before, after)) {
                List<UUID> groupAdditions = new ArrayList<>(after);
                groupAdditions.removeAll(before);
                List<UUID> groupRemovals = new ArrayList<>(before);
                groupRemovals.removeAll(after);
                groupsChanged.put(tenant, new GroupChange(List.copyOf(groupAdditions), List.copyOf(groupRemovals)));
            }
        }

        List<String> configurationChanges = new ArrayList<>();
        Configuration fromConfiguration = from.manifest().configuration();
        Configuration toConfiguration = to.manifest().configuration();
        if (!Objects.equals(fromConfiguration.configVersion(), toConfiguration.configVersion())) {
            configurationChanges.add("configVersion");
        }
        if (!Objects.equals(fromConfiguration.featureFlags(), toConfiguration.featureFlags())) {
            configurationChanges.add("featureFlags");
        }
        if (!Objects.equals(fromConfiguration.tenantConfig(), toConfiguration.tenantConfig())) {
            configurationChanges.add("tenantConfig");
        }

        return new ManifestDiffView(
                new DiffEndpoint(from.releaseId(), from.releaseRef(), from.sha256(), from.frozenAt()),
                new DiffEndpoint(to.releaseId(), to.releaseRef(), to.sha256(), to.frozenAt()),
                artifactChanges,
                new TargetChanges(List.copyOf(added), List.copyOf(removed), groupsChanged),
                fieldChangeOf(
                        ringText(from.manifest().ring()), ringText(to.manifest().ring())),
                fieldChangeOf(from.manifest().environment(), to.manifest().environment()),
                fieldChangeOf(
                        from.manifest().rollout() == null
                                        || from.manifest().rollout().percentage() == null
                                ? null
                                : String.valueOf(from.manifest().rollout().percentage()),
                        to.manifest().rollout() == null
                                        || to.manifest().rollout().percentage() == null
                                ? null
                                : String.valueOf(to.manifest().rollout().percentage())),
                List.copyOf(configurationChanges));
    }

    private static Map<UUID, List<UUID>> groupsByTenant(List<Target> targets) {
        Map<UUID, List<UUID>> groups = new TreeMap<>();
        targets.forEach(target -> groups.put(target.tenantId(), target.testerGroupIds()));
        return groups;
    }

    private static ArtifactPinView pinOf(Artifact artifact) {
        return new ArtifactPinView(
                artifact.buildNumber(), artifact.sha256(), artifact.sizeBytes(), artifact.fileName());
    }

    private static String ringText(Ring ring) {
        return ring == null ? null : ring.code();
    }

    private static FieldChange fieldChangeOf(String from, String to) {
        return Objects.equals(from, to) ? null : new FieldChange(from, to);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormatHolder.FORMAT.formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Lazy holder so the digest formatter never initializes on classload of tests that never hash. */
    private static final class HexFormatHolder {
        static final java.util.HexFormat FORMAT = java.util.HexFormat.of();
    }

    // -------------------------------------------------------------------------
    // The module's standing resolution helpers (the ReleaseService shapes)
    // -------------------------------------------------------------------------

    /** The application profile through the public port (tuples only, never the row): the concealed-miss backstop. */
    private ApplicationAccess.ApplicationProfile profileOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .orElseThrow(() ->
                        new ReleaseException(ReleaseException.Code.APPLICATION_NOT_FOUND, "application not found"));
    }

    /**
     * The release resolved scoped (the seam's bound scope) and verified
     * against the addressed application: any release id that is
     * unknown, out of scope, or belonging to a different application
     * is the standard concealed miss, indistinguishable from an
     * unknown id.
     */
    private ReleaseEntity scopedReleaseOf(UUID applicationId, UUID releaseId) {
        ReleaseEntity release = releases.findScoped(releaseId)
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found"));
        if (!release.getApplicationId().equals(applicationId)) {
            throw new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found");
        }
        return release;
    }

    /** Establishes the application's owning scope and runs the work inside it (the module's executor pattern). */
    private <T> T inScope(ApplicationAccess.ApplicationProfile application, UUID actorId, Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    private static ReleaseException invalid(Map<String, String> errors) {
        return new ReleaseException(ReleaseException.Code.INVALID_REQUEST, "the request is invalid", errors);
    }
}
