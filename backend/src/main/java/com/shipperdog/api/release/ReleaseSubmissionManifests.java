package com.shipperdog.api.release;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The release module's public surface for the frozen store-submission
 * block of a release's manifest (issue #63, S13-03, AC1, PRD 16): the
 * immutable copy of the store-listing metadata and the phased-release
 * decision the APPROVED crossing froze — WHAT WAS SENT, where the
 * store's own surfaces show the version's CURRENT state. The
 * distribution module's dispatch reads this block at the ACTIVE
 * crossing and submits exactly it, so the manifest and the wire can
 * never drift apart.
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit).
 */
public interface ReleaseSubmissionManifests {

    /**
     * The release's frozen store-submission block, read back from the
     * STORED manifest — never recomputed from live rows. Empty when
     * the release carries no manifest (nothing APPROVED yet) or the
     * frozen manifest predates the block's owning story (a document of
     * its time, forever valid).
     */
    Optional<FrozenSubmission> frozenSubmissionOf(UUID releaseId);

    /**
     * The frozen block: the submission channel, the phased-release
     * decision the environment's configuration had in force at the
     * freeze, and the per-locale listing metadata.
     *
     * @param channel       the submission channel the block froze for (e.g. {@code appstore})
     * @param phasedRelease whether the submission was configured to start the store's phased-release curve
     * @param locales       the frozen per-locale listing metadata (possibly empty — the honest empty)
     */
    record FrozenSubmission(String channel, boolean phasedRelease, List<FrozenLocale> locales) {

        public FrozenSubmission {
            locales = List.copyOf(locales);
        }
    }

    /**
     * One frozen submission locale.
     *
     * @param locale         the store locale
     * @param description    the locale's listing description
     * @param keywords       the locale's search keywords (null when none was frozen)
     * @param screenshotRefs the screenshot references the manifest pins
     * @param releaseNotes   the locale's what's-new (release notes; null when none was frozen)
     */
    record FrozenLocale(
            String locale, String description, String keywords, List<String> screenshotRefs, String releaseNotes) {

        public FrozenLocale {
            screenshotRefs = screenshotRefs == null ? List.of() : List.copyOf(screenshotRefs);
        }
    }
}
