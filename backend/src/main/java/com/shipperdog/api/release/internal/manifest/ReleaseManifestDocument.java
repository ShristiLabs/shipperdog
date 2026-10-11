package com.shipperdog.api.release.internal.manifest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The frozen manifest document of PRD 16 (issue #57, S9-03): the
 * complete, self-contained record of WHAT a release ships, assembled
 * by deep-copying every value at the APPROVED crossing — release and
 * version identity, per-platform artifact pins (sha256, build, size,
 * filename), the target set with tester groups, the ring, the
 * environment, the rollout, the required approvals, and the
 * configuration snapshot. Record component order IS the canonical
 * field order: the document serializes through the manifest service's
 * canonical mapper (nulls included, ISO-8601 instants), and the
 * SHA-256 over those bytes is the manifest's identity in the audit
 * trail and the diff surface.
 *
 * <p>Nulls are deliberate, never omissions (AC2 pins every key's
 * presence): since S9-04 (issue #58) a release naming an environment
 * freezes its code and its configuration snapshot's RESOLVED values
 * (see {@link Configuration}); a release naming none keeps the honest
 * nulls. The rollout percentage stays null until E10's staged rollouts,
 * and the required-approvers list stays empty until S10-01's approval
 * policies. A manifest frozen before a block's owning story landed
 * remains a valid document of its time forever (the format's shape
 * never changes).
 *
 * <p>Every field is a copied VALUE, never a live reference: artifacts
 * are identified by content (sha256) beside their uuid, targets by
 * tenant id with their group selections, the ring by its frozen
 * code/name/position — nothing in this tree is read back from mutable
 * rows at retrieval time (AC3), so later renames of the application,
 * future ring vocabulary changes, or E10's ring promotions can never
 * rewrite what a release shipped.
 */
public record ReleaseManifestDocument(
        Release release,
        List<Artifact> artifacts,
        List<Target> targets,
        Ring ring,
        String environment,
        Rollout rollout,
        Configuration configuration,
        Approval approval,
        StoreSubmission storeSubmission) {

    /** The S13-02 shape (no store-submission block): submissions answer null. */
    public ReleaseManifestDocument(
            Release release,
            List<Artifact> artifacts,
            List<Target> targets,
            Ring ring,
            String environment,
            Rollout rollout,
            Configuration configuration,
            Approval approval) {
        this(release, artifacts, targets, ring, environment, rollout, configuration, approval, null);
    }

    /** The release identity block: who shipped what, frozen (PRD 16's release map). */
    public record Release(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            String applicationName,
            UUID versionId,
            String versionName,
            UUID organizationId) {}

    /** One per-platform artifact pin: the binary identified by content, not by address. */
    public record Artifact(
            String platform,
            UUID artifactId,
            String fileName,
            Long buildNumber,
            String sha256,
            long sizeBytes,
            Instant uploadedAt) {}

    /** One target: the tenant and its frozen tester-group selection (empty = the tenant at large). */
    public record Target(UUID tenantId, List<UUID> testerGroupIds) {}

    /** The starting ring as of the freeze: code, display name, and progressive-distribution position. */
    public record Ring(String code, String name, int position) {}

    /**
     * The staged-rollout block (PRD 16's rollout map): the percentage
     * stays null (the simple curve E10 reserved), and since S13-06
     * (issue #66, AC6) a release carrying a staged TENANT rollout plan
     * freezes it here — the widening intent (mode and ordered stages
     * with their resolved cohorts) deep-copied at the APPROVED
     * crossing. The plan is the immutable copy of WHAT WAS PLANNED;
     * the rollout's own append-only ledger (the staged rollout view)
     * records the reached stages through completion — the manifest
     * never rewrites history.
     */
    public record Rollout(Integer percentage, StagedRollout staged) {

        /** The pre-S13-06 shape: no staged plan. */
        public Rollout(Integer percentage) {
            this(percentage, null);
        }
    }

    /** The frozen staged plan: the advancement mode and the ordered stages. */
    public record StagedRollout(String mode, List<StagedStage> stages) {

        public StagedRollout {
            stages = List.copyOf(stages);
        }
    }

    /** One frozen staged stage: its order, shape, and resolved cohort. */
    public record StagedStage(int position, String kind, Integer percent, List<UUID> cohort) {

        public StagedStage {
            cohort = cohort == null ? List.of() : List.copyOf(cohort);
        }
    }

    /**
     * The configuration snapshot block (PRD 16's configuration map,
     * camelCased to the API's conventions). Since S9-04 (issue #58) a
     * release naming an environment freezes the RESOLVED values:
     * {@code configVersion} is the environment's configuration version
     * at the freeze moment (0 when never configured — the honest
     * empty), {@code featureFlags} is the complete resolved key-value
     * map of that snapshot (the actual flag, endpoint, and settings
     * set pinned to the release — deep values, never pointers, the
     * issue's technical note). {@code tenantConfig} stays null until
     * its owning story models per-tenant overrides; a release naming
     * no environment keeps all three null.
     */
    public record Configuration(
            Long configVersion, Map<String, String> featureFlags, Map<String, String> tenantConfig) {}

    /** The approval block: the required-approvers vocabulary (evaluation logic is S10-01's). */
    public record Approval(List<String> required) {}

    /**
     * The store-submission block (PRD 16's WHAT WAS SENT, S13-03,
     * issue #63, AC1): the submission channel the release's
     * environment bound, the phased-release decision its
     * configuration had in force at the freeze, and the per-locale
     * listing metadata the submission will send — deep-copied from the
     * distribution module's authoring rows AT the APPROVED crossing.
     * The dispatch at the ACTIVE crossing sends THIS block verbatim
     * (never the live rows), so the manifest is the immutable copy of
     * the submission while the store's own surfaces show the version's
     * CURRENT state. Null for releases on channels that submit nothing
     * (Direct, TestFlight); present with a possibly-empty locale list
     * for submission channels (the honest empty the submission failure
     * path acts on).
     */
    public record StoreSubmission(String channel, boolean phasedRelease, List<SubmissionLocale> locales) {

        public StoreSubmission {
            locales = List.copyOf(locales);
        }
    }

    /** One frozen submission locale: the listing facts the manifest pins for one store locale. */
    public record SubmissionLocale(
            String locale, String description, String keywords, List<String> screenshotRefs, String releaseNotes) {

        public SubmissionLocale {
            screenshotRefs = screenshotRefs == null ? List.of() : List.copyOf(screenshotRefs);
        }
    }
}
