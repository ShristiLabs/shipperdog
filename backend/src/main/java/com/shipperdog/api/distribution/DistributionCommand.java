package com.shipperdog.api.distribution;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One dispatch of one release through one channel adapter (issue #61,
 * S13-01, FR-019): the complete, self-contained fact set the adapter
 * acts on -- the release's identity and owning dimensions, its frozen
 * artifact slots, its target set, the channel configuration in force,
 * and the credential the delegation chain resolved for the call. An
 * adapter never re-reads platform state to serve a dispatch: what the
 * command carries is what the adapter sees, so a dispatch is
 * reproducible from its record.
 *
 * <p><strong>Secret discipline (PRD 25):</strong> the credential rides
 * as {@link #credential()} -- provider, name, and the secret REFERENCE
 * the chain resolved, with material behind {@link SecretMaterial}
 * whose {@code toString()} is redacted by construction. Adapters
 * resolve material only at the moment they build the provider call,
 * never log it, and never persist it. The framework audits the
 * reference, never the material.
 *
 * <p><strong>Trace discipline (PRD 28):</strong> every dispatch
 * carries {@link #traceId()} and {@link #spanId()} of the observation
 * the framework opened for the adapter call -- store latency is a top
 * debugging question, so the adapter call is its own traced, timed
 * span ({@code shipperdog.adapter.call.duration}), and a store-side
 * correlation id belongs in the {@link AdapterOutcome#externalId()
 * external id} the adapter reports back.
 *
 * @param releaseId      the release being distributed
 * @param releaseRef     the human-quotable release ref ({@code R-<n>})
 * @param applicationId  the application the release distributes
 * @param versionId      the version the release distributes
 * @param organizationId the owning organization (always)
 * @param tenantId       the owning tenant (exactly when tenant-owned)
 * @param environmentId  the targeted environment (null when creation named none)
 * @param environmentCode the targeted environment's stable code (null with environmentId)
 * @param ringCode       the release's resolved ring code (S13-04, #64: the ring-to-track mapping's input; null when the release named no ring)
 * @param channel        the channel reference name this dispatch runs through
 * @param artifacts      the release's frozen artifact slots, one per platform
 * @param targetTenantIds the tenants the release targets (who may receive)
 * @param targetGroups   the release's targeted tester groups with the channel's resolved group links (S13-02; the framework resolves, the adapter assigns)
 * @param channelConfig  the resolved channel configuration in force (key/value, deep values)
 * @param credential     the credential the delegation chain resolved (null when the channel needs none -- Direct)
 * @param traceId        the trace id of the adapter-call observation
 * @param spanId         the span id of the adapter-call observation
 * @param attempt        the 1-based attempt number of this call (retries increment it)
 * @param initiatedAt    when the framework initiated the dispatch
 * @param submission     the release's frozen store-submission block (S13-03 froze it for SUBMISSION channels; S13-04 generalizes the freeze to any channel whose authoring surface produced one — the block is null when the release froze none)
 */
public record DistributionCommand(
        UUID releaseId,
        String releaseRef,
        UUID applicationId,
        UUID versionId,
        UUID organizationId,
        UUID tenantId,
        UUID environmentId,
        String environmentCode,
        String ringCode,
        String channel,
        List<ArtifactSlot> artifacts,
        Set<UUID> targetTenantIds,
        List<TargetGroup> targetGroups,
        java.util.Map<String, String> channelConfig,
        ResolvedCredential credential,
        String traceId,
        String spanId,
        int attempt,
        Instant initiatedAt,
        Submission submission) {

    /** The S13-03 shape (no ring code): the ring code answers null. */
    public DistributionCommand(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            UUID organizationId,
            UUID tenantId,
            UUID environmentId,
            String environmentCode,
            String channel,
            List<ArtifactSlot> artifacts,
            Set<UUID> targetTenantIds,
            List<TargetGroup> targetGroups,
            java.util.Map<String, String> channelConfig,
            ResolvedCredential credential,
            String traceId,
            String spanId,
            int attempt,
            Instant initiatedAt,
            Submission submission) {
        this(
                releaseId,
                releaseRef,
                applicationId,
                versionId,
                organizationId,
                tenantId,
                environmentId,
                environmentCode,
                null,
                channel,
                artifacts,
                targetTenantIds,
                targetGroups,
                channelConfig,
                credential,
                traceId,
                spanId,
                attempt,
                initiatedAt,
                submission);
    }

    /** The S13-02 shape (no store submission): the submission block answers null. */
    public DistributionCommand(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            UUID organizationId,
            UUID tenantId,
            UUID environmentId,
            String environmentCode,
            String channel,
            List<ArtifactSlot> artifacts,
            Set<UUID> targetTenantIds,
            List<TargetGroup> targetGroups,
            java.util.Map<String, String> channelConfig,
            ResolvedCredential credential,
            String traceId,
            String spanId,
            int attempt,
            Instant initiatedAt) {
        this(
                releaseId,
                releaseRef,
                applicationId,
                versionId,
                organizationId,
                tenantId,
                environmentId,
                environmentCode,
                channel,
                artifacts,
                targetTenantIds,
                targetGroups,
                channelConfig,
                credential,
                traceId,
                spanId,
                attempt,
                initiatedAt,
                null);
    }

    /** The S13-01 shape (no targeted tester groups): the group set answers empty. */
    public DistributionCommand(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            UUID organizationId,
            UUID tenantId,
            UUID environmentId,
            String environmentCode,
            String channel,
            List<ArtifactSlot> artifacts,
            Set<UUID> targetTenantIds,
            java.util.Map<String, String> channelConfig,
            ResolvedCredential credential,
            String traceId,
            String spanId,
            int attempt,
            Instant initiatedAt) {
        this(
                releaseId,
                releaseRef,
                applicationId,
                versionId,
                organizationId,
                tenantId,
                environmentId,
                environmentCode,
                channel,
                artifacts,
                targetTenantIds,
                List.of(),
                channelConfig,
                credential,
                traceId,
                spanId,
                attempt,
                initiatedAt,
                null);
    }

    /**
     * One frozen artifact slot of the release: identity, platform, the
     * STRUCTURAL format verdict (FR-006's IPA/APK/AAB vocabulary; the
     * Direct channel's format rule judges on it), the client file
     * name, the stored size, and — since S13-02 (#62), the store
     * connectors' matching keys — the binary's extracted identity:
     * build number, bundle id (Apple) / package name (Google), and the
     * marketing version string (ASC's {@code CFBundleShortVersionString}
     * peer). Null when the extraction tier recorded none.
     *
     * <p>Since S13-04 (#64) the slot also carries {@link #contentOf}
     * — a re-openable stream supplier over the artifact's STORED
     * BYTES, resolved by the framework from the object-storage tier
     * (the adapter never reads platform tables to fetch its binary).
     * Null when the channel needs no bytes (the assignment channels);
     * a supplier must answer a FRESH stream per call so a sandbox
     * retry re-streams from the start.
     */
    public record ArtifactSlot(
            UUID artifactId,
            String platform,
            String format,
            String fileName,
            long sizeBytes,
            Long buildNumber,
            String bundleId,
            String versionName,
            java.util.function.Supplier<java.io.InputStream> contentOf) {

        /** The S13-02 shape (no byte source): the content supplier answers null. */
        public ArtifactSlot(
                UUID artifactId,
                String platform,
                String format,
                String fileName,
                long sizeBytes,
                Long buildNumber,
                String bundleId,
                String versionName) {
            this(artifactId, platform, format, fileName, sizeBytes, buildNumber, bundleId, versionName, null);
        }

        /** The S13-01 shape (identity through size only): the store-extraction facts answer null. */
        public ArtifactSlot(UUID artifactId, String platform, String format, String fileName, long sizeBytes) {
            this(artifactId, platform, format, fileName, sizeBytes, null, null, null, null);
        }
    }

    /**
     * One targeted tester group as the store connectors address it
     * (S13-02, #62): the group, the tenant whose target selected it,
     * and — when the channel keeps a group link — the provider-side
     * group id the channel's mapping surface recorded (null when the
     * group is unmapped on this channel). The FRAMEWORK resolves the
     * link (the adapter never reads platform tables); the adapter
     * assigns builds to the groups that carry one.
     */
    public record TargetGroup(UUID groupId, UUID tenantId, String externalGroupId) {}

    /**
     * The release's frozen store submission as the SUBMISSION-capable
     * channels address it (S13-03, #63): the channel, the phased
     * release flag the environment's configuration had in force at the
     * APPROVED freeze, and the per-locale listing metadata — THE
     * FROZEN MANIFEST BLOCK, read back by the framework exactly as it
     * was frozen, never the live authoring rows. The manifest is the
     * submission's source of truth (PRD 16's immutable copy of what
     * was sent); the adapter that submits anything else is defective
     * by construction. Null for channels that declare no SUBMISSION
     * capability (Direct, TestFlight).
     *
     * @param channel       the submission channel the block froze for
     * @param phasedRelease whether the submission starts the store's phased-release curve
     * @param locales       the frozen per-locale listing metadata (may be empty — the honest empty a submission failure acts on)
     */
    public record Submission(String channel, boolean phasedRelease, List<SubmissionLocale> locales) {

        public Submission {
            locales = List.copyOf(locales);
        }
    }

    /**
     * One frozen submission locale (S13-03, #63): the listing facts the
     * manifest froze for one store locale.
     *
     * @param locale         the store locale (Apple's BCP-47 vocabulary)
     * @param description    the locale's listing description
     * @param keywords       the locale's search keywords (null when none was frozen)
     * @param screenshotRefs the screenshot references the manifest pins
     * @param releaseNotes   the locale's what's-new (release notes; null when none was frozen)
     */
    public record SubmissionLocale(
            String locale, String description, String keywords, List<String> screenshotRefs, String releaseNotes) {

        public SubmissionLocale {
            screenshotRefs = screenshotRefs == null ? List.of() : List.copyOf(screenshotRefs);
        }
    }

    /**
     * The credential the delegation chain resolved for this dispatch
     * (PRD 25: org default, app override, env override --
     * first-defined-wins walking env to app to org). The material is
     * carried, never exposed: {@code toString()} redacts.
     *
     * @param provider        the account's provider (open vocabulary: apple, google-play, ...)
     * @param name            the account's human label
     * @param secretReference the opaque reference the material lives behind
     * @param material        the resolved material, redacted in any log or dump
     * @param metadata        the binding's NON-SECRET metadata JSON (the provider's own
     *                        connection facts, e.g. Apple's issuer id and key id beside
     *                        the .p8 material; never material, the binding surface's rule)
     */
    public record ResolvedCredential(
            String provider, String name, String secretReference, SecretMaterial material, String metadata) {

        /** The S13-01 shape (no metadata): the provider connection facts answer null. */
        public ResolvedCredential(String provider, String name, String secretReference, SecretMaterial material) {
            this(provider, name, secretReference, material, null);
        }

        @Override
        public String toString() {
            return "ResolvedCredential[provider=" + provider + ", name=" + name + ", secretReference=" + secretReference
                    + ", material=" + SecretMaterial.REDACTED + "]";
        }
    }
}
