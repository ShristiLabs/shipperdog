package com.shipperdog.api.distribution;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The distribution-channel adapter SPI (issue #61, S13-01, FR-019,
 * PRD P6/19): the ONE interface every delivery channel -- Direct,
 * TestFlight (#62-#63), Play (#64-#65), future MDMs -- implements, so
 * a new store plugs into distribution without a core change (AC6: a
 * stub adapter in a test context is a {@code @Bean} of this interface
 * and nothing else). The framework owns discovery (every Spring bean
 * of this type joins the registry), sandboxing (per-adapter timeout,
 * bounded retries with backoff keyed on the failure category), trace
 * context, the distribution records, and the audit trail; the adapter
 * owns exactly the provider conversation.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><strong>Identity.</strong> {@link #channelId()} is the
 *       reference-name vocabulary the environment and ring surfaces
 *       already bind ({@code environments.distribution_channel},
 *       V27/V32): a lowercase kebab id ({@code direct}, {@code
 *       testflight}, {@code play}). One id per registry; a duplicate
 *       fails startup. {@code direct} is the reserved default -- a
 *       release whose environment names no channel runs Direct.</li>
 *   <li><strong>Capabilities.</strong> {@link #capabilities()} is the
 *       adapter's declared surface (see {@link AdapterCapability}) --
 *       data for the console catalog, never a dispatch branch.</li>
 *   <li><strong>distribute.</strong> Deliver the command's artifact
 *       slots to its targets through the provider. The call runs
 *       inside the framework's sandbox: the configured timeout walls
 *       it, categories {@code TRANSIENT}/{@code RATE_LIMITED}/{@code
 *       TIMEOUT} earn bounded retries, and every failure exits as
 *       {@link AdapterException} (anything else is {@code PERMANENT}).
 *       The report's per-artifact outcomes drive the distribution
 *       records (AC4): report {@link AdapterOutcome#DISTRIBUTED} with
 *       the provider's external id when the provider side is done,
 *       {@code DISTRIBUTING} when the provider owns the next step --
 *       the framework stores the external id either way so store
 *       reconciliation can poll.</li>
 *   <li><strong>status.</strong> Report the provider-side status of
 *       one previously distributed artifact (the reconciliation half
 *       of AC4; the App Store build-processing and beta-review polls
 *       and the Play track-release polls of #62-#65 ride it). Throws
 *       {@link AdapterException} on provider failure, same taxonomy.</li>
 *   <li><strong>Purity.</strong> An adapter may write to its provider,
 *       never to platform tables: distribution records, credentials,
 *       and audit are the framework's. It must be stateless between
 *       calls (its Spring bean may serve concurrent dispatches) and
 *       must never log credential material (the command's {@link
 *       SecretMaterial} redacts by construction).</li>
 * </ul>
 *
 * <h2>Adding an adapter</h2>
 *
 * Implement this interface as a Spring bean; the registry discovers
 * it at startup and the console catalog, credential resolution, and
 * dispatch follow with zero framework edits. Guide:
 * docs/distribution.md. The provider SDK (App Store Connect API,
 * Google Play Developer API) lives INSIDE the adapter's implementation
 * package -- never in the framework, never in another module (the
 * issue's isolation note).
 */
public interface DistributionChannelAdapter {

    /**
     * The channel's reference id: the lowercase kebab vocabulary the
     * environment and ring surfaces bind ({@code direct}, {@code
     * testflight}, {@code play}). Stable forever once served -- it is
     * stored on distribution records.
     */
    String channelId();

    /** The channel's display name for the console catalog. */
    String displayName();

    /** Human-facing summary of what the channel does (console catalog). */
    String description();

    /** The channel's declared capabilities (console catalog; never a dispatch branch). */
    Set<AdapterCapability> capabilities();

    /**
     * The providers this channel's credential resolution asks the
     * delegation chain for ({@code direct} answers none -- it needs
     * no store account; the store adapters answer their provider
     * vocabulary, e.g. {@code apple}, {@code google-play}). Empty
     * means dispatches on this channel carry no credential.
     */
    default Set<String> credentialProviders() {
        return Set.of();
    }

    /**
     * Delivers one release through the channel. Runs sandboxed: the
     * per-adapter timeout and retry policy apply, trace context rides
     * the command, and failures exit as {@link AdapterException}.
     *
     * @return one outcome per artifact slot of the command, in the
     *         command's order -- the report the records take verbatim
     */
    List<AdapterOutcome> distribute(DistributionCommand command);

    /**
     * Reports the provider-side status of one previously distributed
     * artifact, for store reconciliation (AC4; the App Store
     * build-processing and beta-review polls of #62-#65 ride it).
     * The external id is the one the adapter reported at distribution
     * time; the credential is the one the refresh resolved through
     * the delegation chain for this channel's providers (null when
     * the channel declared none — Direct has no provider to poll).
     * Throws {@link AdapterException} on provider failure, same
     * taxonomy.
     *
     * @return the current outcome as the provider tells it
     */
    AdapterOutcome status(
            UUID artifactId, String channel, String externalId, DistributionCommand.ResolvedCredential credential);

    /**
     * Whether this channel's dispatch follows the release's ring walk
     * onto its PROMOTED crossing (S13-05, #65, PRD 8.10/17): the
     * framework dispatches every adapter at the ACTIVE crossing; an
     * adapter whose provider surface is RING-GRADED — the Play
     * channel, whose ring-to-track map re-targets the same release to
     * a wider track as the walk advances — answers {@code true} and
     * is dispatched again at the ACTIVE to PROMOTED crossing the
     * final ring's landing fires (every target on the final ring —
     * Production-last, the #43 order). Every other adapter keeps the
     * default: one dispatch at ACTIVE, the PROMOTED crossing is
     * nobody else's business (a re-dispatch would duplicate a
     * TestFlight upload or re-run an App Store submission — never
     * accidental).
     */
    default boolean followsPromotionCrossings() {
        return false;
    }

    /**
     * Whether a dispatch at this ring is SUBMISSION-GRADE — the
     * deliverable of THIS dispatch is the store's acceptance, so its
     * permanent refusal vetoes the crossing it rode on (the #63
     * doctrine split, ring-graded by S13-05): the App Store
     * submission channel answers {@code true} for every ring (its
     * one dispatch IS the submission); the Play channel answers
     * {@code true} only for the production ring (a testing-track
     * dispatch keeps the never-fail-the-crossing doctrine — the
     * platform's own availability stands alone on the testing tracks,
     * while the production track release is the store's call).
     * Consulted only beside the {@link AdapterCapability#SUBMISSION}
     * capability; the default {@code false} keeps every ring
     * un-vetoed.
     */
    default boolean submitsAtRing(String ringCode) {
        return false;
    }
}
