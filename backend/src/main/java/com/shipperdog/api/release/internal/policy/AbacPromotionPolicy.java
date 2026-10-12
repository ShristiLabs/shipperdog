package com.shipperdog.api.release.internal.policy;

import com.shipperdog.api.authorization.AbacConsult;
import com.shipperdog.api.authorization.AbacConsult.AbacPromotionRequest;
import com.shipperdog.api.release.ReleasePromotionPolicy;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The ABAC registrant of the promotion seam (issue #70, S14-04, PRD
 * 25/38): the post-RBAC refinement hook for ring hops — the promote
 * route's own {@code @PreAuthorize} check has already decided who may
 * enter the surface, and this contributor narrows WITHIN it, exactly
 * the way the declarative policy engine (S10-06) does. The consult
 * carries the hop's complete release-grain attribute sources — the
 * owning organization, the acting subject, the application, the
 * release's targeted environment, the release (the security-state
 * source), and the ring being ENTERED — so PRD 25's worked example
 * ("Can promote IF user.role = ReleaseManager AND application =
 * Banking AND environment = Production AND security.status =
 * APPROVED") authors as one REQUIRE policy and enforces exactly.
 *
 * <p>Deterministic ordering beside the declarative engine: both are
 * {@code ReleasePromotionPolicy} contributors consulted in bean
 * order; each answers independently, and any refusal renders in the
 * promotion 409's {@code errors} member keyed by its name — the
 * ABAC leg names the winning rule, its version, and the evaluated
 * attributes (AC3's explanation on the promotion surface). The
 * consult fails closed: an engine fault answers a refusal, never a
 * silent pass (AC6).
 */
@Component
public class AbacPromotionPolicy implements ReleasePromotionPolicy {

    private final AbacConsult abac;
    private final ReleaseScopedRepository releases;

    AbacPromotionPolicy(AbacConsult abac, ReleaseScopedRepository releases) {
        this.abac = abac;
        this.releases = releases;
    }

    /**
     * The skip consult (the fail-closed composition): ABAC defers
     * skip WIDTH to the S10-06 knob — its own attribute policies
     * evaluate the NAMED ring the same way whatever the hop's width,
     * so this registrant admits what the knob admits (one refusing
     * contributor would keep the default one-ring-at-a-time posture
     * for every organization, the very regression the skip test
     * pins).
     */
    @Override
    public boolean admitsSkip(PromotionCheck check) {
        return true;
    }

    @Override
    public Optional<String> denial(PromotionCheck check) {
        ReleaseEntity release = releases.findScoped(check.releaseId()).orElse(null);
        if (release == null) {
            // The promotion engine has already resolved the release;
            // an unresolvable hop here is a refusal, never a guess.
            return Optional.of("the ABAC consult could not resolve the release (fail closed)");
        }
        return abac.promotionDenial(new AbacPromotionRequest(
                check.organizationId(),
                check.actorId(),
                check.applicationId(),
                release.getEnvironmentId(),
                release.getId(),
                check.toRing()));
    }
}
