package com.shipperdog.api.gate;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.gate.internal.service.GateService;
import com.shipperdog.api.gate.internal.service.GateService.CreateCommand;
import com.shipperdog.api.gate.internal.service.GateService.DefinitionView;
import com.shipperdog.api.gate.internal.service.GateService.GatesView;
import com.shipperdog.api.gate.internal.service.GateService.ResultCommand;
import com.shipperdog.api.gate.internal.service.GateService.UpdateCommand;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gate API (issue #42, S10-03, FR-026, PRD 32/33): two surfaces
 * in one controller, the workflow module's standing. The definition
 * routes are organization-grain ({@code org:gate-definitions:manage/
 * read}; the permission target is the organization the body/query
 * carries, the item read takes {@code organizationId} as the
 * body-addressed query parameter). The release-addressed routes —
 * result ingestion, the gate read, and the override — carry the
 * application in the body/query, the release surface's own
 * permission-target shape: external CI submits results under
 * {@code app:releases:manage} (FR-022's "external systems may
 * submit"), the dashboard reads under {@code app:releases:read}, and
 * the override demands its own privileged authority
 * ({@code app:releases:gate-override}) — overriding a quality refusal
 * is not release management, it is the governed exception FR-026
 * demands be audited.
 */
@RestController
public class GateController {

    private final GateService gates;

    GateController(GateService gates) {
        this.gates = gates;
    }

    // -------------------------------------------------------------------------
    // Definitions (AC1): the organization's required-gate catalog
    // -------------------------------------------------------------------------

    /**
     * Creates a gate definition (AC1): the gate type (the open
     * vocabulary; the FR-026 built-ins carry documented expected-result
     * schemas), the required flag, the config payload the schema
     * validates (a coverage threshold, a critical-bug ceiling), and the
     * optional ring attachment — PRD 33's "each ring carries its own
     * quality gates". Validations refuse before any write; one
     * definition per organization and gate type at each ring level is
     * the precise 409. Honors {@code Idempotency-Key}.
     */
    @PostMapping("/api/v1/gate-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:gate-definitions:manage')")
    DefinitionView create(@AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return gates.create(
                requireUserId(principal),
                new CreateCommand(
                        request.organizationId(),
                        request.gateType(),
                        request.required(),
                        request.config(),
                        request.ring()));
    }

    /**
     * The definition listing: the organization's catalog, optionally
     * narrowed to one ring's attachment ({@code ring} names the ring
     * code), type order, standard limit/cursor pagination.
     */
    @GetMapping("/api/v1/gate-definitions")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:gate-definitions:read')")
    PageResponse<DefinitionView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String ring,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<DefinitionView> page =
                gates.list(requireUserId(principal), organizationId, ring, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * The definition detail: the definition read back. The
     * {@code organizationId} query parameter carries the permission
     * target (the body-addressed shape); a definition of another
     * organization conceals exactly like an unknown id.
     */
    @GetMapping("/api/v1/gate-definitions/{definitionId}")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:gate-definitions:read')")
    DefinitionView get(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID definitionId,
            @RequestParam UUID organizationId) {
        return gates.get(requireUserId(principal), organizationId, definitionId);
    }

    /**
     * Edits a gate definition: the required flag, the config payload,
     * and the ring attachment (absent {@code ring} DETACHES to the
     * organization-wide standing). The gate type is the identity and
     * never moves. Audited; honors {@code Idempotency-Key}.
     */
    @PutMapping("/api/v1/gate-definitions/{definitionId}")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:gate-definitions:manage')")
    DefinitionView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID definitionId,
            @RequestParam UUID organizationId,
            @RequestBody UpdateRequest request) {
        return gates.update(
                requireUserId(principal),
                organizationId,
                definitionId,
                new UpdateCommand(request.required(), request.config(), request.ring()));
    }

    // -------------------------------------------------------------------------
    // Release gates: ingestion (AC2), the read (AC5), the override (AC3)
    // -------------------------------------------------------------------------

    /**
     * Records one gate result (AC2, FR-022): pass or fail, the
     * evidence URL, the submitter, and the observed value of a
     * threshold/ceiling gate. Idempotent per gate and release as
     * latest-wins-with-history: re-submitting updates the current
     * state and appends the history (a re-run gate never rewrites what
     * was reported). Metric-backed gate types refuse submission —
     * their values resolve from analytics at evaluation time
     * (fail-closed until S11-04's feeds land). Not
     * idempotency-keyed: the recorded state is the replay answer.
     */
    @PostMapping("/api/v1/releases/{releaseId}/gates/{gateType}/result")
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    GateService.GateView submit(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @PathVariable String gateType,
            @RequestBody ResultRequest request) {
        return gates.submit(
                requireUserId(principal),
                request.applicationId(),
                releaseId,
                gateType,
                new ResultCommand(request.status(), request.evidenceUrl(), request.observedValue()));
    }

    /**
     * The release's gate state (AC5, PRD 32): every expected gate of
     * the release's ring beside every recorded result — states,
     * observed values against thresholds, evidence links, override
     * records, and the append-only history. The dashboard's Quality
     * Gates block renders this feed; NO_DATA renders distinctly from
     * FAILED (no data yet is not a bad result — but it blocks all the
     * same).
     */
    @GetMapping("/api/v1/releases/{releaseId}/gates")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    GatesView gates(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return gates.gates(requireUserId(principal), applicationId, releaseId);
    }

    /**
     * Overrides a blocking gate (AC3): the privileged exception path —
     * a FAILED or NO_DATA gate stops blocking with actor, mandatory
     * reason, and moment recorded on the row, surfaced on the
     * dashboard, and audited. Exactly once: a used override is a
     * recorded fact that never moves.
     */
    @PostMapping("/api/v1/releases/{releaseId}/gates/{gateType}/override")
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:gate-override')")
    GateService.GateView override(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @PathVariable String gateType,
            @RequestBody OverrideRequest request) {
        return gates.override(requireUserId(principal), request.applicationId(), releaseId, gateType, request.reason());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new GateException(GateException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The definition creation body (AC1): the owning organization (the
     * permission target), the gate type of the open vocabulary, the
     * required flag (default true — FR-026's "required gates"), the
     * expected-result config ({@code threshold} for the percentage
     * kinds, {@code max} for the count kind, absent for pass/fail),
     * and the optional ring attachment (absent = organization-wide).
     */
    @Schema(
            description = "The gate definition creation body: organization, gate type, required flag, expected-result "
                    + "config, and the optional ring attachment.")
    public record CreateRequest(
            UUID organizationId, String gateType, Boolean required, Map<String, Object> config, String ring) {}

    /** The definition edit body: the mutable facts only (the gate type is the identity). */
    @Schema(description = "The gate definition edit body: required flag, config, and ring attachment.")
    public record UpdateRequest(Boolean required, Map<String, Object> config, String ring) {}

    /**
     * The result submission body (AC2): the permission-target
     * application, the reported outcome ({@code pass} or {@code
     * fail}), the evidence URL (mandatory — the report link), and the
     * observed value a threshold/ceiling gate requires.
     */
    @Schema(
            description = "The gate result submission body: application, outcome, evidence URL, and the observed "
                    + "value of a threshold/ceiling gate.")
    public record ResultRequest(UUID applicationId, String status, String evidenceUrl, Double observedValue) {}

    /** The override body (AC3): the permission-target application and the mandatory reason. */
    @Schema(description = "The gate override body: application and the mandatory reason.")
    public record OverrideRequest(UUID applicationId, String reason) {}
}
