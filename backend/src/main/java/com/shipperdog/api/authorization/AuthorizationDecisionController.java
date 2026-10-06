package com.shipperdog.api.authorization;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin-only decision inspection API (issue #30, S7-01,
 * acceptance criterion 5): runs the layered engine for a supplied
 * decision context and returns the decision with its evaluated chain
 * — every layer's verdict, the failing layer, and the reason code —
 * without serving any resource data. The debugging surface for "why
 * did John's download deny": the same engine, the same audit trail
 * (each inspection is itself a decision and emits
 * {@code AUTHORIZATION_DECISION} with the inspected subject as the
 * audited user), zero side effects on the resources addressed.
 *
 * <p>Platform-administrator only ({@code platform:authorization:
 * inspect} — the platform wildcard): reason codes and the evaluated
 * chain are internal detail (PRD 12 concealment); this surface is
 * their single sanctioned reader. A denial it reports renders as the
 * route's ordinary opaque 403 for anyone else.
 */
@RestController
@RequestMapping("/api/v1/authorization/decisions")
public class AuthorizationDecisionController {

    private final AuthorizationService authorization;

    AuthorizationDecisionController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /**
     * Inspects one decision: the layered chain's verdict for the
     * supplied context, evaluated live (no cache; the PRD 42 SLO
     * applies to the engine, not this surface). Audited with
     * {@code surface=inspect} so an inspection is distinguishable
     * from the serving decision it mirrors.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'audit', 'platform:authorization:inspect')")
    DecisionView inspect(@RequestBody InspectRequest request) {
        AuthorizationContext context = request.toContext();
        return DecisionView.of(authorization.inspect(context));
    }

    /**
     * The decision context to inspect: the subject and the addressed
     * resource ids (any subset — the engine derives the chain
     * downward), the optional platform pin, and the action.
     */
    @Schema(
            description = "The decision context to inspect: the subject user, the addressed "
                    + "tenant/application/version/artifact ids (any subset), the optional platform "
                    + "(IOS|ANDROID), and the action (LIST_APPLICATIONS|READ_ARTIFACT|DOWNLOAD).")
    public record InspectRequest(
            UUID userId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            String platform,
            String action) {

        AuthorizationContext toContext() {
            if (userId == null) {
                throw new AuthorizationInspectionException("userId is required");
            }
            AuthorizationAction parsedAction;
            try {
                parsedAction = AuthorizationAction.valueOf(
                        action == null ? "" : action.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new AuthorizationInspectionException(
                        "action must be one of LIST_APPLICATIONS, READ_ARTIFACT, DOWNLOAD");
            }
            return new AuthorizationContext(
                    userId,
                    tenantId,
                    applicationId,
                    versionId,
                    artifactId,
                    platform == null || platform.isBlank()
                            ? null
                            : platform.trim().toUpperCase(),
                    null,
                    parsedAction);
        }
    }

    /** The inspected decision: outcome, reason, failing layer, and the evaluated chain. */
    public record DecisionView(boolean allowed, String reason, String failingLayer, java.util.List<LayerView> chain) {

        static DecisionView of(AuthorizationDecision decision) {
            return new DecisionView(
                    decision.allowed(),
                    decision.reason() == null ? null : decision.reason().name(),
                    decision.failingLayer(),
                    decision.chain().stream()
                            .map(outcome -> new LayerView(
                                    outcome.layer(),
                                    outcome.status().name(),
                                    outcome.reason() == null
                                            ? null
                                            : outcome.reason().name(),
                                    outcome.detail()))
                            .toList());
        }
    }

    /** One layer's verdict within the inspected chain. */
    public record LayerView(String layer, String status, String reason, String detail) {}

    /** A malformed inspection request (400 INVALID_REQUEST). */
    static final class AuthorizationInspectionException extends RuntimeException {

        AuthorizationInspectionException(String message) {
            super(message);
        }
    }
}
