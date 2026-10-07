package com.shipperdog.api.release.internal.distribution;

import com.shipperdog.api.authorization.AuthorizationDistributionPolicy;
import com.shipperdog.api.release.internal.entity.ReleaseEntity.Status;
import com.shipperdog.api.release.internal.repository.ReleaseRegistryRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The release module's contributor on the authorization engine's
 * distribution layer (issue #56, S9-02, AC4): a ROLLED_BACK
 * direct-channel release cuts off new downloads of its version's
 * artifacts. This is the exact seam S7-01 reserved — "M2's release
 * model registers the real policy against this seam" — so the engine
 * learns nothing about releases and the module registers the one
 * cutoff M2 owns: rollback orchestration for stores is S13-06, and
 * distribution-time windows are E13; both compose as further policies,
 * never as engine changes.
 *
 * <p>The cutoff denies {@code DOWNLOAD} only: rolled-back or not, the
 * version and its artifacts stay readable (INV-006/007 — rollback
 * never deletes, reads remain fully authorized; the portal shows the
 * tester what was pulled, it just refuses to serve it again). The
 * denial renders as the download route's concealed miss shape with the
 * distribution layer's {@code DISTRIBUTION_BLOCKED} reason on the
 * audit trail.
 *
 * <p>A read-only point lookup that never throws — an unavailable
 * source answers not-blocking (the layer restricts; it does not
 * authorize), the seam's own contract.
 */
@Component
public class RolledBackCutoffPolicy implements AuthorizationDistributionPolicy {

    private final ReleaseRegistryRepository releases;

    RolledBackCutoffPolicy(ReleaseRegistryRepository releases) {
        this.releases = releases;
    }

    @Override
    public boolean blocksDownload(UUID artifactId, UUID versionId) {
        if (versionId == null) {
            return false;
        }
        try {
            return releases.existsByVersionIdAndStatus(versionId, Status.ROLLED_BACK);
        } catch (RuntimeException e) {
            // This layer restricts; it does not authorize: an unavailable
            // probe must not fault the engine's decision.
            return false;
        }
    }
}
