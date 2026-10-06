package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.authorization.AuthorizationAction;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.DenialReason;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * PRD 11/12's sixth chain member: <em>Is the artifact available?</em>
 * — the "Android artifact available?" question of the worked example.
 * The layer resolves the addressed artifact and checks:
 *
 * <ul>
 *   <li><strong>Existence and address</strong> — the artifact exists
 *       and belongs to the addressed version; anything else is the
 *       concealed miss.</li>
 *   <li><strong>Platform</strong> — when the request pinned a platform
 *       (PRD 12's Android input), the artifact must carry it.</li>
 *   <li><strong>Availability</strong> — downloads require the
 *       artifact's lifecycle to be settled distributable: READY or
 *       DISTRIBUTED (PRD 18 — a VALIDATING or FAILED artifact is not
 *       downloadable; ARCHIVED is rest). Reads carry no availability
 *       gate: the read-back surface is where extraction settles, so a
 *       VALIDATING artifact is readable metadata.</li>
 * </ul>
 */
@Component
class ArtifactLayer implements AuthorizationLayer {

    /** The distributable lifecycle statuses of PRD 18. */
    private static final Set<String> DOWNLOADABLE = Set.of("READY", "DISTRIBUTED");

    ArtifactLayer() {}

    @Override
    public int order() {
        return 600;
    }

    @Override
    public String name() {
        return "ARTIFACT";
    }

    @Override
    public boolean applicable(EvaluationState state, AuthorizationContext context) {
        return context.artifactId() != null;
    }

    @Override
    public Verdict evaluate(EvaluationState state, AuthorizationContext context) {
        Optional<ArtifactAccess.ArtifactState> resolved = state.artifactStateOf(context.artifactId());
        if (resolved.isEmpty()) {
            return Verdict.failed(DenialReason.ARTIFACT_MISSING, "artifact not found");
        }
        ArtifactAccess.ArtifactState artifact = resolved.get();
        if (context.versionId() != null && !context.versionId().equals(artifact.versionId())) {
            return Verdict.failed(DenialReason.ARTIFACT_MISSING, "artifact belongs to another version");
        }
        if (context.platform() != null && !context.platform().equals(artifact.platform())) {
            return Verdict.failed(DenialReason.ARTIFACT_PLATFORM_MISMATCH, "artifact platform " + artifact.platform());
        }
        if (context.action() == AuthorizationAction.DOWNLOAD && !DOWNLOADABLE.contains(artifact.status())) {
            return Verdict.failed(DenialReason.ARTIFACT_NOT_AVAILABLE, "artifact status " + artifact.status());
        }
        return Verdict.passed("artifact available (status " + artifact.status() + ")");
    }
}
