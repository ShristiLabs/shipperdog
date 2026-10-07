package com.shipperdog.api.release.internal.manifest;

import com.shipperdog.api.release.ReleaseTransitionedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The manifest's freeze hook (issue #57, S9-03, PRD 16): the S9-02
 * composition seam doing exactly what it was reserved for — a listener
 * that reacts to the state machine without the machine ever learning
 * manifest concerns. Observes {@link ReleaseTransitionedEvent}
 * synchronously, so the freeze runs INSIDE the APPROVED crossing's
 * committing transaction and bound scope: the manifest row and the
 * crossing commit or roll back together (AC1 — no APPROVED release
 * without its manifest), and an assembly failure answers loudly with
 * the whole transition undone, never a half-committed approval.
 *
 * <p>The freeze fires on the APPROVED edge only, which PRD 17's
 * forward-only graph makes a once-per-release moment: APPROVED's only
 * outbound edge is DEPLOYING and no edge re-enters it, so the listener
 * cannot double-freeze — and the storage's one-per-release constraint
 * stands behind that as the authority (see
 * {@link ReleaseManifestService#freeze}). Later crossings (DEPLOYING,
 * ACTIVE, ...) publish the same event and this listener stays quiet:
 * the manifest is a document of the approval moment, not a live view
 * the lifecycle keeps rewriting.
 */
@Component
public class ReleaseManifestFreezeListener {

    private final ReleaseManifestService manifests;

    ReleaseManifestFreezeListener(ReleaseManifestService manifests) {
        this.manifests = manifests;
    }

    @EventListener
    void onReleaseTransitioned(ReleaseTransitionedEvent event) {
        if ("APPROVED".equals(event.toStatus())) {
            manifests.freeze(event);
        }
    }
}
