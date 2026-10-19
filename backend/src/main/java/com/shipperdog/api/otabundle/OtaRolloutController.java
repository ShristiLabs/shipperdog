package com.shipperdog.api.otabundle;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.otabundle.internal.service.OtaRolloutService;
import com.shipperdog.api.otabundle.internal.service.OtaRolloutService.AttachCommand;
import com.shipperdog.api.otabundle.internal.service.OtaRolloutService.OtaRolloutView;
import com.shipperdog.api.otabundle.internal.service.OtaRolloutService.StageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OTA rollout control API (S16-06, issue #168, ADR-0010
 * workstream 3, D6/D10): the release's device rollout -- its plan
 * attached and COMMENCED on a live OTA release, then driven by four
 * operator actions. Every act is audited, lands on the plan's
 * append-only timeline, and publishes a bus event; HALT is the
 * manual, audit-trailed stop of D10 (nothing auto-halts). Devices are
 * never listed or stored: the S16-07 check route resolves membership
 * on the fly from the plan's standing and the deterministic D6
 * bucketing.
 *
 * <p>Routes are release-addressed under the module's {@code /ota}
 * family: the body's (or read's) {@code applicationId} carries the
 * permission target, and the permission is the release family's --
 * {@code app:releases:manage} for the control surface, {@code
 * app:releases:read} for the status view (the RELEASE_MANAGER and
 * APP_OWNER personas it fits). Foreign or unknown ids are the
 * concealed miss; the not-live or not-OTA release is the 409 of the
 * module's own registry codes. Honors {@code Idempotency-Key} on
 * every POST; concurrent control on one plan answers the
 * optimistic-lock 409 (the V48 pattern), never a lost update.
 */
@RestController
@RequestMapping("/api/v1/ota/rollouts")
public class OtaRolloutController {

    private final OtaRolloutService rollouts;

    OtaRolloutController(OtaRolloutService rollouts) {
        this.rollouts = rollouts;
    }

    /**
     * Attaches the release's rollout plan -- and thereby COMMENCES it:
     * the plan is born RUNNING on a live (ACTIVE or PROMOTED) OTA
     * release, stage 1 REACHED, a COMMENCED timeline row. One plan per
     * release, ever; stages are the cumulative basis-point ladder
     * (strictly ascending, the final stage 10000). Honors {@code
     * Idempotency-Key}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    OtaRolloutView attachRollout(@AuthenticationPrincipal Object principal, @RequestBody AttachRolloutRequest request) {
        return rollouts.attach(
                requireUserId(principal),
                new AttachCommand(
                        request.applicationId(),
                        request.releaseId(),
                        request.mode(),
                        request.stages() == null
                                ? List.of()
                                : request.stages().stream()
                                        .map(stage -> new StageRequest(stage.basisPoints(), stage.note()))
                                        .toList()));
    }

    /**
     * The rollout's status view: the plan's mode and standing, every
     * stage of the ladder, and the append-only timeline. The {@code
     * applicationId} query parameter is the permission target; a
     * foreign or unknown release is the concealed miss.
     */
    @GetMapping("/{releaseId}")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    OtaRolloutView rolloutStatus(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestParam("applicationId") UUID applicationId) {
        return rollouts.view(requireUserId(principal), applicationId, releaseId);
    }

    /**
     * Advances one stage: the next cumulative basis-point threshold
     * widens the serving set (deterministically -- a device inside a
     * stage never leaves it). An AUTOMATIC plan consults the policy
     * engine first; a CHECKPOINT advance IS the approval. The final
     * stage's advancement completes the rollout. Honors {@code
     * Idempotency-Key}.
     */
    @PostMapping("/{releaseId}/advance")
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    OtaRolloutView advanceStage(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestBody ControlRequest request) {
        return rollouts.control(requireUserId(principal), request.applicationId(), releaseId, "ADVANCE", null);
    }

    /**
     * Halts the rollout (D10): the manual, audit-trailed stop -- the
     * reason is mandatory, and the S16-07 check resolution answers
     * no-update while the plan stands HALTED. A paused plan is still
     * stoppable. Nothing halts on its own, ever. Honors {@code
     * Idempotency-Key}.
     */
    @PostMapping("/{releaseId}/halt")
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    OtaRolloutView haltRollout(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestBody ControlRequest request) {
        return rollouts.control(requireUserId(principal), request.applicationId(), releaseId, "HALT", request.reason());
    }

    /**
     * Pauses the rollout: the temporary stop -- serving freezes at the
     * current stage and resumes exactly where it froze (the ladder
     * never retreats). Honors {@code Idempotency-Key}.
     */
    @PostMapping("/{releaseId}/pause")
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    OtaRolloutView pauseRollout(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestBody ControlRequest request) {
        return rollouts.control(
                requireUserId(principal), request.applicationId(), releaseId, "PAUSE", request.reason());
    }

    /**
     * Resumes the rollout: from HALTED or PAUSED back to RUNNING,
     * serving what the plan had reached (resuming widens nothing by
     * itself). Honors {@code Idempotency-Key}.
     */
    @PostMapping("/{releaseId}/resume")
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    OtaRolloutView resumeRollout(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestBody ControlRequest request) {
        return rollouts.control(
                requireUserId(principal), request.applicationId(), releaseId, "RESUME", request.reason());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The attachment body: the owning application and the release the
     * plan hangs on, the advancement mode (AUTOMATIC: the policy
     * engine's verdict between stages; CHECKPOINT: an operator's
     * explicit approval), and the cumulative basis-point ladder.
     */
    @Schema(
            description = "The OTA rollout plan to attach and commence: applicationId, releaseId, the mode"
                    + " (AUTOMATIC or CHECKPOINT), and the stages -- each a cumulative basisPoints threshold"
                    + " of 1..10000, strictly ascending, the final stage 10000.")
    public record AttachRolloutRequest(UUID applicationId, UUID releaseId, String mode, List<StageBody> stages) {

        /** One ladder stage: its cumulative threshold and the operator's note. */
        @Schema(description = "One stage: cumulative basisPoints (1..10000) and the optional note.")
        public record StageBody(Integer basisPoints, String note) {}
    }

    /** The control body: the permission target and the action's optional (halt: mandatory) reason. */
    @Schema(description = "The applicationId the plan's release belongs to, and the reason (mandatory for halt).")
    public record ControlRequest(UUID applicationId, String reason) {}
}
