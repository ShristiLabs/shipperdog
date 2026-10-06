package com.shipperdog.api.artifact.internal.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity.Platform;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity.Status;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The PRD 18 transition table, exhaustively (issue #24, S5-05): every
 * ordered pair of the six-value vocabulary is asserted against the
 * exact table — legal pairs pass, every other pair is rejected loudly
 * by {@code requireLegal} — plus the entity-side clients of the
 * machine: the write-once format verdict (a divergent re-observation
 * is a parser regression, never a data fact), the revalidation entry
 * (FAILED moves back to VALIDATING; an already-VALIDATING row is the
 * in-flight no-op), and the extraction-block clearing guard.
 */
class ArtifactStateMachineTest {

    /** PRD 18 exactly; ARCHIVED is terminal rest. */
    private static final Map<Status, Set<Status>> LEGAL = Map.of(
            Status.UPLOADING, Set.of(Status.VALIDATING),
            Status.VALIDATING, Set.of(Status.READY, Status.FAILED),
            Status.FAILED, Set.of(Status.VALIDATING),
            Status.READY, Set.of(Status.DISTRIBUTED, Status.ARCHIVED),
            Status.DISTRIBUTED, Set.of(Status.ARCHIVED),
            Status.ARCHIVED, Set.of());

    @Test
    void theTransitionTableIsPrd18Exactly() {
        for (Status from : Status.values()) {
            for (Status to : Status.values()) {
                boolean legal = LEGAL.get(from).contains(to);
                assertThat(ArtifactStateMachine.isLegal(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(legal);
                if (legal) {
                    assertThatCode(() -> ArtifactStateMachine.requireLegal(from, to))
                            .as("requireLegal %s -> %s", from, to)
                            .doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> ArtifactStateMachine.requireLegal(from, to))
                            .as("requireLegal %s -> %s", from, to)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(from.name())
                            .hasMessageContaining(to.name());
                }
            }
        }
    }

    @Test
    void theVocabularyCarriesNoRevokedState() {
        // PRD 18 has no REVOKED: revocation is E7's authorization
        // lifecycle (INV-007), never an artifact-row state.
        assertThat(Status.values())
                .containsExactlyInAnyOrder(
                        Status.UPLOADING,
                        Status.VALIDATING,
                        Status.READY,
                        Status.DISTRIBUTED,
                        Status.ARCHIVED,
                        Status.FAILED);
    }

    @Test
    void theFormatVerdictIsWriteOnceAndDivergenceIsLoud() {
        ArtifactEntity artifact = artifact(Status.VALIDATING);
        artifact.recordFormat("APK");
        assertThat(artifact.getFormat()).isEqualTo("APK");
        // The same verdict again is the idempotent re-observation.
        artifact.recordFormat("APK");
        assertThat(artifact.getFormat()).isEqualTo("APK");
        // A DIFFERENT verdict over an immutable binary is a fault.
        assertThatThrownBy(() -> artifact.recordFormat("AAB"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APK")
                .hasMessageContaining("AAB");
        assertThat(artifact.getFormat()).isEqualTo("APK");
    }

    @Test
    void revalidationEntersThroughTheMachineAndNoOpsInFlight() {
        ArtifactEntity failed = artifact(Status.FAILED);
        failed.beginRevalidation();
        assertThat(failed.getStatus()).isEqualTo(Status.VALIDATING);
        // An already-VALIDATING row re-drives without a transition.
        failed.beginRevalidation();
        assertThat(failed.getStatus()).isEqualTo(Status.VALIDATING);
        // A READY row cannot revalidate: PRD 18 has no path back.
        ArtifactEntity ready = artifact(Status.READY);
        assertThatThrownBy(ready::beginRevalidation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extractionBlocksClearOnlyOnTheStillValidatingBlockedShape() {
        ArtifactEntity blocked = artifact(Status.VALIDATING);
        blocked.noteExtractionBlocked("{\"code\":\"EXTRACTION_QUEUE_FULL\",\"message\":\"full\"}");
        blocked.clearExtractionBlock();
        assertThat(blocked.getExtractionError()).isNull();
        assertThat(blocked.extractionOutcome()).isEqualTo("PENDING");

        // A FAILED extraction outcome is immutable history.
        ArtifactEntity failed = artifact(Status.VALIDATING);
        failed.recordExtractionFailure("{\"code\":\"NOT_A_ZIP\",\"message\":\"x\"}");
        assertThatThrownBy(failed::clearExtractionBlock).isInstanceOf(IllegalStateException.class);

        // A successful extraction has no block to clear.
        ArtifactEntity extracted = artifact(Status.VALIDATING);
        extracted.applyExtraction("com.acme.banking", "7.12.0", "15.0", 421L, "{}");
        assertThatThrownBy(extracted::clearExtractionBlock).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theValidationOutcomeComposesThroughTheMachine() {
        ArtifactEntity validating = artifact(Status.VALIDATING);
        validating.applyValidationOutcome(true, "{\"outcome\":\"PASSED\"}");
        assertThat(validating.getStatus()).isEqualTo(Status.READY);
        assertThat(validating.getValidation()).isEqualTo("{\"outcome\":\"PASSED\"}");

        // The extraction tier failed the row first; the pipeline's
        // same-target write is the legal no-op that carries the doc.
        ArtifactEntity extractionFailed = artifact(Status.VALIDATING);
        extractionFailed.recordExtractionFailure("{\"code\":\"NOT_A_ZIP\",\"message\":\"x\"}");
        extractionFailed.applyValidationOutcome(false, "{\"outcome\":\"FAILED\"}");
        assertThat(extractionFailed.getStatus()).isEqualTo(Status.FAILED);
        assertThat(extractionFailed.getValidation()).isEqualTo("{\"outcome\":\"FAILED\"}");
    }

    private static ArtifactEntity artifact(Status status) {
        return new ArtifactEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Platform.IOS,
                "app.ipa",
                "key",
                "0".repeat(64),
                100L,
                null,
                status,
                UUID.randomUUID(),
                null);
    }
}
