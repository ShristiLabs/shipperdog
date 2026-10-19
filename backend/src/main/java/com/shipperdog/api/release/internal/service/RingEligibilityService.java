package com.shipperdog.api.release.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.release.ReleaseException;
import com.shipperdog.api.release.RingEligibility;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetGroupEntity;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetGroupRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetRepository;
import com.shipperdog.api.release.internal.repository.RingRepository;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.tester.TesterGroupAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ring-eligibility seam's implementation (issue #43, S10-04, AC2/
 * AC4): the admission arithmetic — at ring X, distribution targets =
 * release targets INTERSECT ring eligibility — resolved against the
 * ORGANIZATION-EFFECTIVE ring of every target's current ring (the V24
 * shadowing contract: a system ring an organization redefined under
 * the same code answers the organization ring's rules).
 *
 * <p>The composition discipline (the story's technical note): the ring
 * admits the RELEASE side — which target tenants and, through the
 * group axis, which tester cohorts a release's current ring carries.
 * The E7 grants still admit the TESTER side at distribution time
 * (S7-01's artifact-layer policy composes here, it is never replaced):
 * both must pass. The tester decision this service answers
 * ({@link #admitsTester}) is the ring-and-target leg only, plus the
 * tester's own live membership standing (the two-switches rule — a
 * suspended tester sees nothing, the same live check the
 * authorization engine honors).
 *
 * <p>Reads are seam-served ({@code findScoped}): a release unknown to
 * the caller's bound scope answers empty — the concealed miss the
 * callers render as their own, never a confirmed exclusion.
 */
@Service
public class RingEligibilityService implements RingEligibility {

    private final ReleaseScopedRepository releases;
    private final ReleaseTargetRepository targets;
    private final ReleaseTargetGroupRepository targetGroups;
    private final RingRepository rings;
    private final ApplicationAccess applications;
    private final TenantScopeExecutor scopeExecutor;
    private final TesterGroupAccess testerGroups;
    private final TenantTesterMembershipAccess memberships;
    private final TesterAccess testers;

    RingEligibilityService(
            ReleaseScopedRepository releases,
            ReleaseTargetRepository targets,
            ReleaseTargetGroupRepository targetGroups,
            RingRepository rings,
            ApplicationAccess applications,
            TenantScopeExecutor scopeExecutor,
            TesterGroupAccess testerGroups,
            TenantTesterMembershipAccess memberships,
            TesterAccess testers) {
        this.releases = releases;
        this.targets = targets;
        this.targetGroups = targetGroups;
        this.rings = rings;
        this.applications = applications;
        this.scopeExecutor = scopeExecutor;
        this.testerGroups = testerGroups;
        this.memberships = memberships;
        this.testers = testers;
    }

    // -------------------------------------------------------------------------
    // The route entry: the console's release eligibility read
    // -------------------------------------------------------------------------

    /**
     * The release eligibility view (the route form of the seam): the
     * admission rows of the addressed release — verified against the
     * addressed application and served inside its owning scope, the
     * release module's standing — plus, when the query names a tester,
     * the ring-visibility decision for that one tester (AC4's
     * inspectable form; the portal consumes the same arithmetic
     * through the seam when S10-05 surfaces releases).
     */
    @Transactional(readOnly = true)
    public EligibilityView eligibility(UUID actorId, UUID applicationId, UUID releaseId, UUID testerId) {
        // The route is release-addressed: its application and release
        // misses carry the release surface's own codes byte-identically
        // (the GateException precedent — one vocabulary per miss kind),
        // re-rendered verbatim by this module's advice.
        ApplicationAccess.ApplicationProfile application = applications
                .profileOf(applicationId)
                .orElseThrow(() ->
                        new ReleaseException(ReleaseException.Code.APPLICATION_NOT_FOUND, "application not found"));
        return inScope(application, actorId, () -> {
            ReleaseEntity release = releases.findScoped(releaseId)
                    .orElseThrow(() -> new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found"));
            if (!release.getApplicationId().equals(applicationId)) {
                throw new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found");
            }
            Admission admission = admissionRowsOf(release);
            Boolean admitsTester =
                    testerId == null ? null : admitsTester(releaseId, testerId).orElse(false);
            return new EligibilityView(
                    release.getId(), release.releaseRef(), release.getStatus().name(), admission, admitsTester);
        });
    }

    /** The route view: the release summary, the admission rows, and the optional tester decision. */
    public record EligibilityView(
            UUID releaseId, String releaseRef, String status, Admission admission, Boolean admitsTester) {}

    /** Establishes the application's owning scope and runs the work inside it (the release module's executor pattern). */
    private <T> T inScope(ApplicationAccess.ApplicationProfile application, UUID actorId, Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    // -------------------------------------------------------------------------
    // The seam (AC2): the release's per-target admission
    // -------------------------------------------------------------------------

    /**
     * The release's per-target admission at each target's current ring:
     * the tenant axis first (a target tenant the ring's rule does not
     * list is excluded), then the group axis (the ring's rule narrows
     * the target's audience to its intersection; a target whose group
     * selection and the rule share no group, or whose tenant carries
     * none of the rule's groups, is excluded with the reason). A ring
     * with no eligibility rule admits every target (the seeded
     * standing). Unknown and out-of-scope releases answer empty.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Admission> admissionOf(UUID releaseId) {
        return releases.findScoped(releaseId).map(release -> admissionRowsOf(release));
    }

    /**
     * Whether the current rings admit one tester (AC4, the visibility
     * rule): one admitted target tenant the tester is EFFECTIVELY
     * ACTIVE in, whose audience is the tenant at large or carries one
     * of the tester's groups. Empty when the release is unknown or out
     * of scope; a tester no ring admits answers FALSE (an unknown
     * tester is admitted by nothing, not an error).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Boolean> admitsTester(UUID releaseId, UUID testerId) {
        return admissionOf(releaseId).map(admission -> {
            if (testerId == null) {
                return false;
            }
            Set<UUID> testerGroupsOf = new LinkedHashSet<>(testerGroups.groupIdsOfTester(testerId));
            for (Admission.TargetAdmission target : admission.targets()) {
                if (!target.admitted()) {
                    continue;
                }
                if (!memberships.isEffectivelyActive(target.tenantId(), testerId)) {
                    continue;
                }
                if (target.tenantAtLarge() || target.audienceGroups().stream().anyMatch(testerGroupsOf::contains)) {
                    return true;
                }
            }
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // The device axis (S16-05): the OTA check resolution's leg
    // -------------------------------------------------------------------------

    /**
     * The device admission (S16-05, issue #167 AC2): the release's
     * CURRENT standing — every DISTINCT organization-effective ring its
     * targets occupy — checked against each ring rule's device axes.
     * A device is admitted when ANY occupied ring's rule admits it
     * (the widest standing the release currently holds, the same
     * honest arithmetic the tester leg runs over admitted targets); a
     * ring with no device axis admits every device, a rule naming
     * neither the reported bundle identifier nor the platform tag
     * excludes. Unknown and out-of-scope releases answer empty — the
     * concealed miss. The bundle identifier axis matches exactly
     * (identifiers are case-sensitive strings, not codes); the
     * platform-tag axis matches the tag's lowercase standing (V55's
     * open vocabulary is lowercase by convention, the platform-tag
     * write surface's own normalization).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Boolean> admitsDevice(UUID releaseId, String bundleId, String platformTag) {
        return releases.findScoped(releaseId).map(release -> {
            Map<String, RingEntity> effectiveRings = new LinkedHashMap<>();
            boolean admitted = false;
            for (ReleaseTargetEntity target : targets.findByReleaseIdOrderByTenantIdAsc(release.getId())) {
                RingEntity effective = effectiveRingOf(effectiveRings, release.getOrganizationId(), target.getRingId());
                if (effective == null) {
                    continue;
                }
                if (deviceAdmittedBy(effective, bundleId, platformTag)) {
                    admitted = true;
                    break;
                }
            }
            return admitted;
        });
    }

    /**
     * One ring's device verdict: an absent axis unconstrained, a
     * present axis an exact-member test. Stored rules were validated at
     * write; a malformed row (hand-edited data) fails toward the
     * STRICTER reading — it matches nothing, like the id axes.
     */
    private static boolean deviceAdmittedBy(RingEntity ring, String bundleId, String platformTag) {
        Set<String> ruleBundleIds = textsOf(ring.getEligibility(), "bundleIds");
        Set<String> rulePlatformTags = lowercase(textsOf(ring.getEligibility(), "platformTags"));
        if (ruleBundleIds != null && (bundleId == null || !ruleBundleIds.contains(bundleId))) {
            return false;
        }
        if (rulePlatformTags != null
                && (platformTag == null
                        || !rulePlatformTags.contains(platformTag.trim().toLowerCase(java.util.Locale.ROOT)))) {
            return false;
        }
        return true;
    }

    /** One string axis of a rule as the exact member set: null when the rule carries no axis (unconstrained). */
    private static Set<String> textsOf(Map<String, Object> eligibility, String axis) {
        if (eligibility == null) {
            return null;
        }
        Object value = eligibility.get(axis);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> texts = new LinkedHashSet<>();
        for (Object element : list) {
            if (element != null && !element.toString().isBlank()) {
                texts.add(element.toString());
            }
        }
        return texts;
    }

    /** The lowercase standing of a tag set (the platform-tag vocabulary's own normalization). */
    private static Set<String> lowercase(Set<String> tags) {
        if (tags == null) {
            return null;
        }
        Set<String> lower = new LinkedHashSet<>();
        tags.forEach(tag -> lower.add(tag.toLowerCase(java.util.Locale.ROOT)));
        return lower;
    }

    // -------------------------------------------------------------------------
    // Helpers: the admission arithmetic
    // -------------------------------------------------------------------------

    /**
     * The widening check (S10-05, issue #44 — the seam the RingEligibility
     * contract reserved for the promotion engine): every target's
     * admission row resolved against ONE named ring, the ring a
     * promotion would move the targets INTO — the same arithmetic
     * {@link #admissionRowsOf} runs per target's CURRENT ring, answered
     * here against the hop's destination so the precheck lists exactly
     * which tenants the target ring's eligibility would exclude (each
     * with the reason). Package-private by design: the promotion engine
     * is this module's own consumer (the port's javadoc names it), a
     * sibling service in this package — the seam's public shape stays
     * the live-ring read.
     */
    List<Admission.TargetAdmission> admissionsAgainst(UUID releaseId, RingEntity ring) {
        ReleaseEntity release = releases.findScoped(releaseId).orElse(null);
        if (release == null) {
            return List.of();
        }
        List<ReleaseTargetEntity> targetRows = targets.findByReleaseIdOrderByTenantIdAsc(releaseId);
        Map<UUID, List<UUID>> groupsByTenant = new LinkedHashMap<>();
        for (ReleaseTargetGroupEntity group : targetGroups.findByReleaseIdOrderByTenantIdAscGroupIdAsc(releaseId)) {
            groupsByTenant
                    .computeIfAbsent(group.getTenantId(), tenant -> new ArrayList<>())
                    .add(group.getGroupId());
        }
        List<Admission.TargetAdmission> rows = new ArrayList<>(targetRows.size());
        for (ReleaseTargetEntity target : targetRows) {
            rows.add(admissionOf(target, ring, groupsByTenant.getOrDefault(target.getTenantId(), List.of())));
        }
        return rows;
    }

    /** The admission rows of one release: every target with its effective ring, verdict, audience, and reasons. */
    private Admission admissionRowsOf(ReleaseEntity release) {
        List<ReleaseTargetEntity> targetRows = targets.findByReleaseIdOrderByTenantIdAsc(release.getId());
        Map<UUID, List<UUID>> groupsByTenant = new LinkedHashMap<>();
        for (ReleaseTargetGroupEntity group :
                targetGroups.findByReleaseIdOrderByTenantIdAscGroupIdAsc(release.getId())) {
            groupsByTenant
                    .computeIfAbsent(group.getTenantId(), tenant -> new ArrayList<>())
                    .add(group.getGroupId());
        }
        Map<String, RingEntity> effectiveRings = new LinkedHashMap<>();
        List<Admission.TargetAdmission> rows = new ArrayList<>(targetRows.size());
        String releaseRing = null;
        for (ReleaseTargetEntity target : targetRows) {
            RingEntity effective = effectiveRingOf(effectiveRings, release.getOrganizationId(), target.getRingId());
            if (effective == null) {
                // The referenced ring is gone from the vocabulary (not
                // reachable while the reference exists — the delete
                // guard refuses); the fail-closed answer is exclusion.
                rows.add(new Admission.TargetAdmission(
                        target.getTenantId(), null, false, List.of(), false, List.of("ring no longer resolves")));
                continue;
            }
            if (releaseRing == null) {
                releaseRing = effective.getCode();
            }
            rows.add(admissionOf(target, effective, groupsByTenant.getOrDefault(target.getTenantId(), List.of())));
        }
        return new Admission(release.getId(), release.releaseRef(), releaseRing, List.copyOf(rows));
    }

    /** One target's admission row against one effective ring's rules. */
    private Admission.TargetAdmission admissionOf(
            ReleaseTargetEntity target, RingEntity ring, List<UUID> targetGroupsOfTenant) {
        List<String> reasons = new ArrayList<>();
        Set<UUID> ringTenants = idsOf(ring.getEligibility(), "tenants");
        Set<UUID> ringGroups = idsOf(ring.getEligibility(), "groups");

        boolean admitted = true;
        if (ringTenants != null && !ringTenants.contains(target.getTenantId())) {
            admitted = false;
            reasons.add("tenant is not in the ring's eligibility");
        }
        List<UUID> audience;
        boolean tenantAtLarge;
        if (ringGroups == null) {
            audience = List.copyOf(targetGroupsOfTenant);
            tenantAtLarge = targetGroupsOfTenant.isEmpty();
        } else {
            tenantAtLarge = false;
            if (targetGroupsOfTenant.isEmpty()) {
                // The tenant at large, narrowed by the ring's group rule:
                // the audience becomes the rule's groups of THIS tenant.
                audience = new ArrayList<>();
                for (UUID groupId : ringGroups) {
                    if (target.getTenantId()
                            .equals(testerGroups.owningTenantOf(groupId).orElse(null))) {
                        audience.add(groupId);
                    }
                }
                if (audience.isEmpty()) {
                    admitted = false;
                    reasons.add("the ring's eligibility lists no group of this tenant");
                }
            } else {
                audience = new ArrayList<>(targetGroupsOfTenant);
                audience.retainAll(ringGroups);
                if (audience.isEmpty()) {
                    admitted = false;
                    reasons.add("the target's groups and the ring's eligibility share no group");
                }
            }
        }
        return new Admission.TargetAdmission(
                target.getTenantId(),
                ring.getCode(),
                admitted,
                List.copyOf(audience),
                tenantAtLarge,
                List.copyOf(reasons));
    }

    /**
     * The organization-effective ring of a ring id: the row's code
     * resolved organization-preferred (the V24 contract), memoized per
     * evaluation — every target of one release resolves through the
     * same shadowing rule the creation surface used.
     */
    private RingEntity effectiveRingOf(Map<String, RingEntity> memo, UUID organizationId, UUID ringId) {
        RingEntity referenced = rings.findById(ringId).orElse(null);
        if (referenced == null) {
            return null;
        }
        return memo.computeIfAbsent(
                referenced.getCode(), code -> rings.byCode(code, organizationId).orElse(referenced));
    }

    /** One eligibility axis as id sets: null when the ring carries no rule on the axis (unconstrained). */
    private static Set<UUID> idsOf(Map<String, Object> eligibility, String axis) {
        if (eligibility == null) {
            return null;
        }
        Object value = eligibility.get(axis);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (Object element : list) {
            try {
                ids.add(UUID.fromString(element.toString()));
            } catch (IllegalArgumentException e) {
                // Stored rules were validated at write; a malformed row
                // (hand-edited data) fails toward the STRICTER reading:
                // an unparseable id matches nothing.
                continue;
            }
        }
        return ids;
    }
}
