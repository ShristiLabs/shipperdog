package com.shipperdog.api.otabundle;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.otabundle.internal.service.OtaAlertService;
import com.shipperdog.api.otabundle.internal.service.OtaAlertService.AlertRuleDetailView;
import com.shipperdog.api.otabundle.internal.service.OtaAlertService.AlertRuleView;
import com.shipperdog.api.otabundle.internal.service.OtaAlertService.RuleCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OTA alert-rule API (S16-11, issue #173, ADR-0010 D10): the
 * persisted watches over the application's OTA metrics -- created,
 * listed, read with their firing history, reshaped, and deleted --
 * so the degradation the rollups record surfaces on the console
 * badge, the tenant feed, and the notification bus. The evaluation
 * itself is the scheduler's (beside the rollups); nothing any rule
 * detects ever halts a rollout (D10) -- the halt stays the operator's
 * manual, audit-trailed act through the S16-06 endpoints.
 *
 * <p>Routes ride the module's {@code /ota} family with the release
 * family's permissions: {@code app:releases:manage} for the writes
 * (the RELEASE_MANAGER and APP_OWNER personas that drive rollouts),
 * {@code app:releases:read} for the reads. The {@code applicationId}
 * (body member or query parameter) carries the permission target;
 * foreign or unknown ids are the concealed miss of the module's own
 * codes. Honors {@code Idempotency-Key} on the POST; the PUT and
 * DELETE are idempotent by shape.
 */
@RestController
@RequestMapping("/api/v1/ota/alert-rules")
public class OtaAlertController {

    private final OtaAlertService alerts;

    OtaAlertController(OtaAlertService alerts) {
        this.alerts = alerts;
    }

    /**
     * Creates a rule: the dictionary metric, the comparator, the
     * threshold, the lookback window, and the severity a crossing
     * raises -- optionally narrowed to one bundle of the application
     * (NULL watches the funnel as a whole). Honors {@code
     * Idempotency-Key}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    AlertRuleView createRule(@AuthenticationPrincipal Object principal, @RequestBody AlertRuleRequest request) {
        return alerts.create(
                requireUserId(principal),
                new RuleCommand(
                        request.applicationId(),
                        request.otaBundleId(),
                        request.metric(),
                        request.comparator(),
                        request.threshold(),
                        request.windowMinutes(),
                        request.severity()));
    }

    /**
     * The application's rules, newest first, each with its standing
     * firing -- the console rule editor's list. The {@code
     * applicationId} query parameter is the permission target.
     */
    @GetMapping
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    PageResponse<AlertRuleView> listRules(
            @AuthenticationPrincipal Object principal,
            @RequestParam("applicationId") UUID applicationId,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        return alerts.list(requireUserId(principal), applicationId, query.limit(), query.offset());
    }

    /**
     * One rule with its bounded firing history (newest first) -- the
     * console's rule timeline. The {@code applicationId} query
     * parameter is the permission target; a foreign or unknown rule
     * is the concealed miss.
     */
    @GetMapping("/{ruleId}")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    AlertRuleDetailView getRule(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID ruleId,
            @RequestParam("applicationId") UUID applicationId) {
        return alerts.get(requireUserId(principal), applicationId, ruleId);
    }

    /**
     * Replaces the rule's whole watch shape -- the retire-without-
     * losing-history remedy (reshape to a threshold it can never
     * cross); a rule that has fired refuses the DELETE.
     */
    @PutMapping("/{ruleId}")
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    AlertRuleView updateRule(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID ruleId,
            @RequestBody AlertRuleRequest request) {
        return alerts.update(
                requireUserId(principal),
                request.applicationId(),
                ruleId,
                new RuleCommand(
                        request.applicationId(),
                        request.otaBundleId(),
                        request.metric(),
                        request.comparator(),
                        request.threshold(),
                        request.windowMinutes(),
                        request.severity()));
    }

    /**
     * Deletes a rule that has never fired (the mis-authored watch's
     * own remedy); a rule with firing history answers the module's
     * 409 -- the trail stays addressable through its rule.
     */
    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:manage')")
    void deleteRule(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID ruleId,
            @RequestParam("applicationId") UUID applicationId) {
        alerts.delete(requireUserId(principal), applicationId, ruleId);
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
     * The create/update body: the owning application, the optional
     * bundle scope, and the watch's whole shape (the closed metric
     * dictionary and vocabularies validated field-addressed).
     */
    @Schema(
            description = "The alert rule to create or replace: applicationId, the optional otaBundleId scope, and"
                    + " the watch -- metric (OTA_CHECKS, OTA_SEEN, OTA_DOWNLOADS, OTA_APPLIED, OTA_FAILURES,"
                    + " OTA_ROLLBACKS, OTA_APPLY_FAILURE_RATE, OTA_CRASH_FREE_RATE), comparator (GT, GTE, LT,"
                    + " LTE), threshold, windowMinutes (1..10080), severity (INFO, WARNING, CRITICAL).")
    public record AlertRuleRequest(
            UUID applicationId,
            String otaBundleId,
            String metric,
            String comparator,
            Double threshold,
            Integer windowMinutes,
            String severity) {}
}
