package com.shipperdog.api.release;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestService;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestService.ManifestDiffView;
import com.shipperdog.api.release.internal.manifest.ReleaseManifestService.ManifestView;
import com.shipperdog.api.release.internal.service.PromotionService;
import com.shipperdog.api.release.internal.service.PromotionService.PrecheckView;
import com.shipperdog.api.release.internal.service.PromotionService.PromoteCommand;
import com.shipperdog.api.release.internal.service.PromotionService.PromotionHistoryView;
import com.shipperdog.api.release.internal.service.PromotionService.PromotionResultView;
import com.shipperdog.api.release.internal.service.ReleaseService;
import com.shipperdog.api.release.internal.service.ReleaseService.ArtifactSlot;
import com.shipperdog.api.release.internal.service.ReleaseService.CreateCommand;
import com.shipperdog.api.release.internal.service.ReleaseService.ReleaseDetail;
import com.shipperdog.api.release.internal.service.ReleaseService.ReleaseView;
import com.shipperdog.api.release.internal.service.ReleaseService.TargetCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The release API (issue #55, S9-01, FR-015, PRD 8.6/19): one
 * body-addressed collection route and two reads, the artifact-collection
 * precedent — {@code POST /api/v1/releases} carries the application id
 * in the JSON body, so the permission check targets the application
 * ({@code app:releases:manage} through the evaluator's
 * application-to-owner hop) and the service establishes the owning
 * scope through the sanctioned executor once the application resolves.
 *
 * <p>Creation assembles the whole distribution event in one governed
 * act: the version, one READY artifact per platform (S5-05), the target
 * tenants (each must hold the application, S3-03 — per-tenant errors
 * ride one 422), each target's tester groups, and the starting ring
 * (S10-04's ring definitions, pulled forward as reference data by this
 * story). The release is born DRAFT — the state machine is S9-02 — and
 * its creation is audited with the full target set (FR-024). The
 * quotable id (AC5, {@code R-1001}) rides every view beside the uuid
 * the routes address. Creation honors {@code Idempotency-Key} (the
 * designated-POST registry, per the API conventions).
 *
 * <p>The listing requires {@code applicationId} — it is the permission
 * target and the listing's scope — and narrows conjunctively by
 * {@code versionId}, {@code status}, and {@code tenantId} (AC5),
 * standard limit/cursor pagination. The detail carries the application
 * id as the {@code applicationId} query parameter for the same
 * body-addressed permission-target shape the artifact read established;
 * a release of another application is the standard concealed miss.
 *
 * <p>The state machine (issue #56, S9-02, PRD 17) rides the same
 * shapes: {@code POST /api/v1/releases/{id}/transitions} moves a
 * release along PRD 17's edges only ({@code app:releases:manage}; the
 * illegal crossing answers 409 with current and attempted states, the
 * failure/rollback reason is mandatory), and
 * {@code GET /api/v1/releases/{id}/history} returns the append-only
 * transition timeline with every crossing's actor, reason, and
 * timestamp ({@code app:releases:read}).
 *
 * <p>The manifest (issue #57, S9-03, PRD 16) composes on the state
 * machine's event hook: every APPROVED crossing freezes the release's
 * immutable document (deep-copied values, content-hashed, audited),
 * and {@code GET /api/v1/releases/{id}/manifest} serves the STORED
 * document in JSON (default) or YAML ({@code format=yaml}), with
 * {@code GET /api/v1/releases/{id}/manifest/diff?otherReleaseId=…}
 * answering what moved between two freezes ({@code app:releases:read}
 * both).
 */
@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseController {

    private final ReleaseService releases;

    private final ReleaseManifestService manifests;

    private final PromotionService promotions;

    ReleaseController(ReleaseService releases, ReleaseManifestService manifests, PromotionService promotions) {
        this.releases = releases;
        this.manifests = manifests;
        this.promotions = promotions;
    }

    /**
     * Creates a DRAFT release (issue #55 AC1-AC4, AC6): validations
     * refuse before any write — the version must belong to the
     * application and be releasable (not archived, distribution not
     * revoked), every artifact READY and of the version, every target
     * tenant holding the application, every tester group belonging to
     * its target tenant, and the ring defined. One release exists per
     * application and version (V1's unique constraint; a duplicate is
     * the precise 409). Returns 201 with the detail, quotable id
     * included.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    ReleaseDetail create(@AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return releases.create(
                requireUserId(principal),
                new CreateCommand(
                        request.applicationId(),
                        request.versionId(),
                        request.ring(),
                        request.environmentId(),
                        request.artifacts() == null
                                ? List.of()
                                : request.artifacts().stream()
                                        .map(artifact -> new ArtifactSlot(artifact.platform(), artifact.artifactId()))
                                        .toList(),
                        request.targets() == null
                                ? List.of()
                                : request.targets().stream()
                                        .map(target -> new TargetCommand(target.tenantId(), target.testerGroupIds()))
                                        .toList()));
    }

    /**
     * The release listing (issue #55 AC5): newest (quotable number)
     * first, conjunctive filters — {@code applicationId} required (the
     * permission target), {@code versionId}, {@code status}, and
     * {@code tenantId} optional. Standard limit/cursor pagination; an
     * unknown status value is a 400, never a silently empty page.
     */
    @GetMapping
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    PageResponse<ReleaseView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID applicationId,
            @RequestParam(required = false) UUID versionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<ReleaseView> page = releases.list(
                requireUserId(principal), applicationId, versionId, status, tenantId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * The release detail: the full creation record — ring, per-platform
     * artifacts, and the complete target set with tester groups (the
     * audited facts, read back). The {@code applicationId} query
     * parameter carries the permission target (the body-addressed
     * shape); a release of another application conceals exactly like an
     * unknown id.
     */
    @GetMapping("/{releaseId}")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    ReleaseDetail get(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return releases.get(requireUserId(principal), applicationId, releaseId);
    }

    /**
     * One release transition (issue #56 AC1-AC3, PRD 17): the body
     * names the target state and, for the failure and rollback edges,
     * the mandatory reason. The crossing must be one of PRD 17's
     * edges — an illegal attempt answers 409 with the current and
     * attempted states in the {@code errors} member (including
     * re-sending a state the release holds and every exit from a
     * terminal); a missing reason on FAILED or ROLLED_BACK is the 422;
     * an unknown target state the 400. Serialized against concurrent
     * writers by the database compare-and-set, audited per crossing,
     * and answered with the refreshed detail. Not idempotency-keyed:
     * the state itself is the replay answer (the lifecycle precedent).
     */
    @PostMapping("/{releaseId}/transitions")
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    ReleaseDetail transition(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestBody TransitionRequest request) {
        return releases.transition(
                requireUserId(principal), request.applicationId(), releaseId, request.to(), request.reason());
    }

    /**
     * The release's transition history (issue #56 AC5, FR-024): the
     * release summary plus the chronological append-only timeline —
     * the creation's DRAFT row and every committed crossing with its
     * actor, reason where present, and timestamp. The
     * {@code applicationId} query parameter carries the permission
     * target (the body-addressed shape); a release of another
     * application conceals exactly like an unknown id.
     */
    @GetMapping("/{releaseId}/history")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    ReleaseService.ReleaseHistoryView history(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return releases.history(requireUserId(principal), applicationId, releaseId);
    }

    /**
     * The release's frozen manifest (issue #57 AC3, PRD 16): the STORED
     * document read back — never recomputed from live rows — verified
     * against its recorded SHA-256 before serving. The response is the
     * storage metadata (digest, frozen moment, the actor who crossed
     * into APPROVED) beside the deep-copied PRD 16 document: artifact
     * pins, targets, ring, environment, rollout, required approvals,
     * and the configuration snapshot. {@code format=yaml} renders the
     * same stored document as YAML (the compliance export); the default
     * JSON is the same bytes' first serialization. A release that has
     * not crossed into APPROVED answers the honest
     * {@code 404 RELEASE_MANIFEST_NOT_FOUND}; a foreign release
     * conceals exactly like an unknown id.
     */
    @GetMapping("/{releaseId}/manifest")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    ResponseEntity<?> manifest(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestParam UUID applicationId,
            @RequestParam(required = false) String format) {
        ManifestView view = manifests.manifest(requireUserId(principal), applicationId, releaseId);
        String render =
                format == null || format.isBlank() ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if ("json".equals(render)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(view);
        }
        if ("yaml".equals(render)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/yaml"))
                    .body(manifests.yaml(view));
        }
        throw new ReleaseException(
                ReleaseException.Code.INVALID_REQUEST,
                "the request is invalid",
                Map.of("format", "format must be json or yaml"));
    }

    /**
     * The diff between this release's frozen manifest and another's of
     * the same application (issue #57 AC5): the artifact pins, target
     * set, and configuration blocks that moved between the two APPROVED
     * freezes — a statement about two frozen moments, never about live
     * state. Both sides must have frozen manifests (the honest
     * {@code 404 RELEASE_MANIFEST_NOT_FOUND} otherwise); a foreign
     * otherReleaseId conceals exactly like an unknown id; diffing a
     * release against itself is the 400 keyed by {@code otherReleaseId}.
     */
    @GetMapping("/{releaseId}/manifest/diff")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    ManifestDiffView manifestDiff(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestParam UUID applicationId,
            @RequestParam UUID otherReleaseId) {
        return manifests.diff(requireUserId(principal), applicationId, releaseId, otherReleaseId);
    }

    /**
     * One ring promotion (issue #44 AC1-AC3, S10-05, FR-018, PRD 33):
     * the governed ring-to-ring move. The body names the permission
     * target (the application id), optionally the SELECTIVE tenant set
     * (absent moves every target — per-target rings are the staged
     * rollout groundwork) and optionally the asserted target ring
     * (must equal the effective order's next ring; a farther ring is
     * the skip refusal — disallowed by default). The engine evaluates
     * the target ring's gates, approvals, eligibility, and promotion
     * policies: any unmet leg answers 409 with the COMPLETE blocker
     * list in the {@code errors} member, every blocker named with its
     * evidence, the refused attempt recorded on the append-only
     * promotions timeline. A passing hop moves the targets' rings,
     * records the hop, audits with the actor, and — when it lands the
     * final ring for every target — carries the release across PRD
     * 17's ACTIVE to PROMOTED edge. Requires {@code
     * app:releases:manage}. Not idempotency-keyed: the targets' rings
     * are the replay answer (the lifecycle precedent).
     */
    @PostMapping("/{releaseId}/promote")
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    PromotionResultView promote(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestBody PromoteRequest request) {
        return promotions.promote(
                requireUserId(principal),
                request.applicationId(),
                releaseId,
                new PromoteCommand(request.tenants(), request.to()));
    }

    /**
     * The promotion precheck (issue #44's technical note: one
     * evaluation function, three surfaces): the SAME evaluation the
     * promote act runs, answered as a read — the next ring, the
     * verdict, the complete blocker list (byte-for-byte the 409's
     * {@code errors} member), the target ring's gate states with
     * their evidence URLs, the approval leg's standing, and the
     * widened eligibility rows. Nothing is written and no approval
     * round is opened. The console's "why can't I promote" screen and
     * the CLI's precheck render this verbatim. Requires {@code
     * app:releases:read}.
     */
    @GetMapping("/{releaseId}/promotion-precheck")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    PrecheckView promotionPrecheck(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return promotions.precheck(requireUserId(principal), applicationId, releaseId);
    }

    /**
     * The promotion history (issue #44 AC5, PRD 18): the release's
     * append-only promotions timeline — every COMPLETED hop with its
     * two rings, moved tenants, and actor, and every BLOCKED attempt
     * with its recorded evidence — the record the release dashboard
     * renders beside the status timeline. The {@code applicationId}
     * query parameter carries the permission target; a release of
     * another application is the standard concealed miss.
     */
    @GetMapping("/{releaseId}/promotions")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    PromotionHistoryView promotions(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return promotions.history(requireUserId(principal), applicationId, releaseId);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new ReleaseException(ReleaseException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The creation body (FR-015; the environment dimension since
     * S9-04/issue #58): the application and version the release
     * distributes, the starting ring's code (the FR-017 vocabulary —
     * engineering, qa, tenant-pilot, uat, production — until an
     * organization customizes), the OPTIONAL environment the release
     * targets (when present, every target tenant must sit inside the
     * environment's assignments and the environment must be ACTIVE),
     * one artifact per platform (every artifact READY and of the
     * version), and the target set — each target a tenant that holds
     * the application plus its tester-group narrowing (an empty group
     * list targets the tenant at large).
     */
    @Schema(
            description = "The release creation body: application, version, starting ring code, optional target "
                    + "environment, per-platform artifact selection (READY artifacts of the version), and target "
                    + "tenants with their tester groups.")
    public record CreateRequest(
            UUID applicationId,
            UUID versionId,
            String ring,
            UUID environmentId,
            List<ArtifactRequest> artifacts,
            List<TargetRequest> targets) {}

    /** One per-platform artifact slot: the platform and the artifact of the version selected for it. */
    public record ArtifactRequest(String platform, UUID artifactId) {}

    /** One target: the tenant and its tester groups (empty targets the tenant at large). */
    public record TargetRequest(UUID tenantId, List<UUID> testerGroupIds) {}

    /**
     * The transition body (PRD 17): the application id (the permission
     * target, the body-addressed shape), the target state of PRD 17's
     * vocabulary, and the reason — mandatory on the failure
     * (VALIDATING/DEPLOYING to FAILED) and rollback (ACTIVE to
     * ROLLED_BACK) crossings, optional on every other.
     */
    @Schema(
            description = "The release transition body: the permission-target application id, the target state "
                    + "of PRD 17's vocabulary, and the reason (mandatory for failure and rollback transitions).")
    public record TransitionRequest(UUID applicationId, String to, String reason) {}

    /**
     * The promote body (S10-05, FR-018): the permission-target
     * application id, the optional SELECTIVE tenant set (absent moves
     * every target), and the optional asserted target ring code
     * (must equal the effective order's next ring — a farther ring is
     * the skip refusal, disallowed by default).
     */
    @Schema(
            description = "The ring-promotion body: the permission-target application id, the optional selective "
                    + "tenant set, and the optional asserted target ring code (the effective order's next ring).")
    public record PromoteRequest(UUID applicationId, List<UUID> tenants, String to) {}
}
