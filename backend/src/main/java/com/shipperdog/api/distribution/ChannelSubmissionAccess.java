package com.shipperdog.api.distribution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The distribution module's public surface for the release manifest's
 * store-submission block (issue #63, S13-03, AC1, PRD 16): the
 * authored store-listing metadata a SUBMISSION-capable channel's
 * release freezes at its APPROVED crossing. The manifest is the
 * immutable copy of WHAT WAS SENT -- ASC shows the version's current
 * state, the manifest shows the submission -- and this port is the
 * single read the freeze builds that block from, so the manifest and
 * the dispatch (which sends THE FROZEN BLOCK, never the live rows) can
 * never disagree.
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit).
 */
public interface ChannelSubmissionAccess {

    /**
     * The application's authored submission metadata on a channel, as
     * the manifest freeze reads it at the APPROVED crossing. Empty
     * when the channel is not a submission channel (no registered
     * adapter declares the SUBMISSION capability for it) -- the
     * manifest's store-submission block stays absent for those
     * releases; a submission channel with no authored locales answers
     * a present snapshot with an empty locale list (the honest empty
     * the submission failure path acts on, never a silent absence).
     */
    Optional<SubmissionSnapshot> snapshotOf(UUID applicationId, String channel);

    /**
     * The snapshot the manifest freezes: the channel's authored
     * locales, deep-copied as values at the read.
     *
     * @param locales the authored locales, locale-ordered
     */
    record SubmissionSnapshot(List<SubmissionLocale> locales) {

        public SubmissionSnapshot {
            locales = List.copyOf(locales);
        }
    }

    /**
     * One authored locale: the store-listing facts a submission sends
     * for it.
     *
     * @param locale         the store locale (Apple's BCP-47 vocabulary, e.g. {@code en-US})
     * @param description    the locale's listing description
     * @param keywords       the locale's search keywords (null when none authored)
     * @param screenshotRefs the screenshot references the manifest pins (ASC-side assets upload through ASC's own surfaces)
     * @param releaseNotes   the locale's what's-new (release notes; null when none authored)
     */
    record SubmissionLocale(
            String locale, String description, String keywords, List<String> screenshotRefs, String releaseNotes) {

        public SubmissionLocale {
            screenshotRefs = screenshotRefs == null ? List.of() : List.copyOf(screenshotRefs);
        }
    }
}
