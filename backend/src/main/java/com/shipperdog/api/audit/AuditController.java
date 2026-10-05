package com.shipperdog.api.audit;

import com.shipperdog.api.audit.internal.service.AuditQueryService;
import com.shipperdog.api.audit.internal.service.AuditQueryService.AuditEventView;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit trail query API (issue #6): {@code GET /api/v1/audit} is the
 * compliance reviewer's reconstruction surface — who did what, when, from
 * where, and why (PRD 27, FR-024).
 *
 * <p>Restricted to platform administrators via
 * {@code platform:audit:read}. Filters map the acceptance criteria's
 * vocabulary: {@code actor} (who), {@code tenant} / {@code organization}
 * (tenancy context), entity references ({@code applicationId} /
 * {@code versionId} / {@code artifactId} plus {@code eventType} for the
 * "what"), and {@code from} / {@code to} for the time range. All filters
 * are conjunctive and optional; results are newest-first and paged with
 * the platform's {@code limit}/{@code cursor} convention (issue #8).
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'audit', 'platform:audit:read')")
    PageResponse<AuditEventView> search(
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) UUID organization,
            @RequestParam(required = false) UUID tenant,
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) UUID versionId,
            @RequestParam(required = false) UUID artifactId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<AuditEventView> result = audit.search(
                actor,
                organization,
                tenant,
                eventType,
                applicationId,
                versionId,
                artifactId,
                from,
                to,
                query.offset(),
                query.limit());
        return PageResponse.of(result.getContent(), result.hasNext(), query.offset() + query.limit());
    }
}
