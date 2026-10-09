package com.shipperdog.api.feedback;

import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.feedback.internal.service.FeedbackService;
import com.shipperdog.api.feedback.internal.service.FeedbackService.AttachmentContent;
import com.shipperdog.api.feedback.internal.service.FeedbackService.FeedbackRollupView;
import com.shipperdog.api.feedback.internal.service.FeedbackService.FeedbackSummaryView;
import com.shipperdog.api.feedback.internal.service.FeedbackService.FeedbackView;
import com.shipperdog.api.feedback.internal.service.FeedbackService.SubmissionCommand;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The feedback API (issues #46/#47, S11-01/S11-02, FR-027, PRD
 * 8.13): one self-scoped submission route, the release-addressed
 * console reads (listing, detail, attachment stream, and the
 * dashboard rollup), and — since S11-02 — the three triage actions
 * (severity, assignee, lifecycle) behind {@code
 * app:feedback:triage}.
 *
 * <p>Submission ({@code POST /api/v1/feedback}, multipart — the
 * artifact upload route's shape) is the portal persona's surface:
 * self-scoped ({@code isAuthenticated()}, the platform's convention
 * for caller-addressed routes — the tester persona holds no RBAC
 * role by design, S6-03), the caller IS the subject, and the
 * multipart fields carry only what the tester types and chooses —
 * the release link arrives as the application/version/tenant the
 * portal screen addresses, the server resolves everything else
 * (AC2). The contract is deliberately the one the S15-01 in-app SDK
 * will POST from inside a shipped build: one API, two clients, no
 * portal-specific fields. Attachments ride the same request as
 * optional file parts ({@code attachments}), each validated against
 * the extension and per-kind size limits before any write (AC3).
 *
 * <p>The console reads are release-addressed
 * ({@code GET /api/v1/releases/{releaseId}/feedback...}), the gate
 * surface's shape: the {@code applicationId} query parameter is the
 * permission target ({@code app:feedback:read} through the
 * application-to-owner hop), the service establishes the owning
 * scope, and an unknown, foreign, or mismatched release conceals as
 * the standard miss. The listing narrows by {@code type}, {@code
 * status}, and {@code tenantId} conjunctively (AC4), standard
 * limit/cursor pagination; SECURITY_ISSUE reports are invisible to
 * every reader below the restricted bar (AC6) — the listing omits
 * them and the detail conceals them as the genuine miss. The
 * attachment download streams to authorized readers only (AC3).
 */
@RestController
public class FeedbackController {

    private final FeedbackService feedback;

    FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    /**
     * Submits one report (AC1): the six-type vocabulary with a
     * non-empty description, the release link the caller's screen
     * addresses, the optional build (a READY artifact of the
     * release's version) and reported device/OS free text, and the
     * optional attachments. Returns 201 with the stored record — the
     * server-attached context included, proving the client never
     * supplied an identity field.
     */
    @PostMapping(value = "/api/v1/feedback", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    FeedbackView submit(
            @AuthenticationPrincipal Object principal,
            @RequestParam("application_id") UUID applicationId,
            @RequestParam("version_id") UUID versionId,
            @RequestParam("tenant_id") UUID tenantId,
            @RequestParam(value = "artifact_id", required = false) UUID artifactId,
            @RequestParam("type") String type,
            @RequestParam("description") String description,
            @RequestParam(value = "device_model", required = false) String deviceModel,
            @RequestParam(value = "os", required = false) String os,
            @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments) {
        return feedback.submit(
                principal,
                new SubmissionCommand(
                        applicationId, versionId, tenantId, artifactId, type, description, deviceModel, os),
                attachments);
    }

    /**
     * The release's feedback listing (AC4): conjunctive filters by
     * type, status, severity, and submitter tenant, newest first,
     * standard limit/cursor pagination. SECURITY_ISSUE rows are
     * absent for readers below the restricted bar (AC6).
     */
    @GetMapping("/api/v1/releases/{releaseId}/feedback")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:feedback:read')")
    PageResponse<FeedbackSummaryView> list(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestParam UUID applicationId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return feedback.list(
                principal, applicationId, releaseId, type, status, severity, tenantId, ListQuery.of(limit, cursor));
    }

    /**
     * The release-dashboard Feedback block's rollup (S11-02, AC2,
     * PRD 32's "14 reports . 1 critical"): counts by severity and
     * status beside the open-critical count the gate metric answers —
     * folded over exactly the rows the calling reader can see (the
     * security-issue restriction applies to counts as it does to
     * rows).
     */
    @GetMapping("/api/v1/releases/{releaseId}/feedback/rollup")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:feedback:read')")
    FeedbackRollupView rollup(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return feedback.rollup(principal, applicationId, releaseId);
    }

    /**
     * Classifies the report (S11-02, AC1): the severity vocabulary —
     * a closed report refuses until re-opened, and the crossing to
     * CRITICAL publishes the critical-feedback event (AC5) beside the
     * audit.
     */
    @PostMapping("/api/v1/releases/{releaseId}/feedback/{feedbackId}/severity")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:feedback:triage')")
    FeedbackView setSeverity(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @PathVariable UUID feedbackId,
            @RequestParam UUID applicationId,
            @RequestBody SeverityRequest request) {
        return feedback.setSeverity(principal, applicationId, releaseId, feedbackId, request.severity());
    }

    /**
     * Assigns the member who works the report (S11-02, AC1) — a null
     * {@code assigneeUserId} clears it; the candidate must belong to
     * the owning organization.
     */
    @PostMapping("/api/v1/releases/{releaseId}/feedback/{feedbackId}/assignee")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:feedback:triage')")
    FeedbackView assign(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @PathVariable UUID feedbackId,
            @RequestParam UUID applicationId,
            @RequestBody AssigneeRequest request) {
        return feedback.assign(principal, applicationId, releaseId, feedbackId, request.assigneeUserId());
    }

    /**
     * Moves the report through its lifecycle (S11-02, AC1): the
     * enforced edges OPEN to TRIAGED to RESOLVED or REJECTED, every
     * closed state re-openable to TRIAGED, the closing note mandatory
     * — each crossing audited with its actor.
     */
    @PostMapping("/api/v1/releases/{releaseId}/feedback/{feedbackId}/transition")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:feedback:triage')")
    FeedbackView transition(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @PathVariable UUID feedbackId,
            @RequestParam UUID applicationId,
            @RequestBody TransitionRequest request) {
        return feedback.transition(
                principal,
                applicationId,
                releaseId,
                feedbackId,
                new FeedbackService.TransitionCommand(request.to(), request.note()));
    }

    /** One triage classification (S11-02, AC1). */
    record SeverityRequest(String severity) {}

    /** One triage assignment (S11-02, AC1) — null clears. */
    record AssigneeRequest(UUID assigneeUserId) {}

    /** One triage lifecycle crossing (S11-02, AC1) — the note mandatory on the closing edges. */
    record TransitionRequest(String to, String note) {}

    /**
     * The report's detail: the full record with its attachments. A
     * SECURITY_ISSUE report for a reader below the restricted bar
     * conceals as the genuine miss (AC6).
     */
    @GetMapping("/api/v1/releases/{releaseId}/feedback/{feedbackId}")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:feedback:read')")
    FeedbackView get(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @PathVariable UUID feedbackId,
            @RequestParam UUID applicationId) {
        return feedback.get(principal, applicationId, releaseId, feedbackId);
    }

    /**
     * Streams one attachment to an authorized reader (AC3): the
     * report's own authorization applies (including the
     * security-issue restriction), then the bytes stream with the
     * stored serving facts — the extension-resolved content type and
     * the tester's file name as the disposition.
     */
    @GetMapping("/api/v1/releases/{releaseId}/feedback/{feedbackId}/attachments/{attachmentId}")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:feedback:read')")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @PathVariable UUID feedbackId,
            @PathVariable UUID attachmentId,
            @RequestParam UUID applicationId) {
        AttachmentContent content = feedback.download(principal, applicationId, releaseId, feedbackId, attachmentId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(content.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(sanitize(content.fileName()))
                .build());
        return ResponseEntity.ok().headers(headers).body(output -> {
            try (var input = content.content()) {
                input.transferTo(output);
            }
        });
    }

    /**
     * The disposition filename is quoted into an HTTP header value:
     * control characters and quotes are stripped so a hostile upload
     * name cannot smuggle header syntax (the name was already
     * bounded at submission; this is the belt to that braces — the
     * artifact store's sanitize precedent).
     */
    private static String sanitize(String fileName) {
        return fileName == null ? "attachment" : fileName.replaceAll("[\"\\\\\\r\\n\\t;]", "_");
    }
}
