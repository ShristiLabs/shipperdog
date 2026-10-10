package com.shipperdog.api.distribution;

/**
 * What a channel adapter can do (issue #61, S13-01, FR-019/020/021):
 * the capability flags the registry reports and the console renders,
 * so a channel's surface is data an operator reads, never tribal
 * knowledge. The M1 Direct channel carries exactly one capability; the
 * store adapters (#62-#65) declare the rest as their connectors grow
 * them.
 *
 * <p>Capabilities are advisory facts about the ADAPTER, not promises
 * about a specific account: a TestFlight adapter that can upload
 * builds still refuses an upload whose credentials lack the App Store
 * Connect role for it. The dispatch layer never branches on a
 * capability it did not ask the adapter about; the flags exist so
 * callers know what to ask for.
 */
public enum AdapterCapability {
    /** Upload a build artifact to the provider (App Store Connect build upload, Play AAB upload). */
    BUILD_UPLOAD,
    /** Report the provider-side status of an uploaded build (processing, beta review, rejected). */
    BUILD_STATUS,
    /** Synchronize tester groups the channel addresses (TestFlight groups, Play tester lists). */
    TESTER_GROUP_SYNC,
    /** Submit a build for store review/release (App Store submission, Play production release). */
    SUBMISSION,
    /** Control a staged rollout's percentage or halt (Play staged rollout, App Store phased release). */
    ROLLOUT_CONTROL,
    /**
     * Serve installs directly through ShipperDog's own signed-URL
     * download tier (the Direct channel; PRD 19's constraint note:
     * ShipperDog always owns authorization and evidence, the channel
     * owns installation mechanics).
     */
    DIRECT_ISSUANCE
}
