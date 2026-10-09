package com.shipperdog.api.feedback.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.feedback.FeedbackAttachmentStore;
import com.shipperdog.api.feedback.FeedbackException;
import com.shipperdog.api.feedback.FeedbackMarkedCriticalEvent;
import com.shipperdog.api.feedback.FeedbackReceivedEvent;
import com.shipperdog.api.feedback.internal.entity.FeedbackAttachmentEntity;
import com.shipperdog.api.feedback.internal.entity.FeedbackAttachmentEntity.Category;
import com.shipperdog.api.feedback.internal.entity.FeedbackEntity;
import com.shipperdog.api.feedback.internal.entity.FeedbackEntity.Severity;
import com.shipperdog.api.feedback.internal.entity.FeedbackEntity.Status;
import com.shipperdog.api.feedback.internal.entity.FeedbackEntity.Type;
import com.shipperdog.api.feedback.internal.repository.FeedbackAttachmentRepository;
import com.shipperdog.api.feedback.internal.repository.FeedbackRepository;
import com.shipperdog.api.feedback.internal.storage.FeedbackStorageProperties;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseAccess.ReleaseFacts;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The feedback surface's service (issues #46/#47, S11-01/S11-02,
 * FR-027, PRD 8.13): the submission act, the console reads, and —
 * since S11-02 — the triage actions (severity, assignment, the
 * enforced lifecycle) with the release-dashboard rollup, all built on
 * the V25 child doctrine — the parent release is resolved first
 * (through the release module's ports) and isolation is enforced
 * there, never re-derived over the children.
 *
 * <p>Submission is the tester-self-scoped surface (the decision-API
 * pattern): the caller IS the subject. Every identity field is
 * resolved server-side — the tester record from the session's user
 * id, the submitting tenant from the caller's own validated
 * membership, the application/version/artifact context from the
 * release link — and a client-supplied identity field is not even
 * part of the contract (AC2). The release link
 * ({@code ReleaseAccess.submissionFactsOf}) is the submission's
 * entire standing: the release must target the named tenant, and the
 * tester must hold an effective ACTIVE membership there (the
 * two-switches rule, live). The write happens inside the tester's
 * own sanctioned tenant scope (the body-carried-tenant creation
 * precedent, the executor), the landing is audited as
 * {@code FEEDBACK_RECEIVED}, and the in-process
 * {@link FeedbackReceivedEvent} publishes beside it (S12-01's bus
 * relays later). The contract is the one the S15-01 in-app SDK will
 * POST — one API, two clients.
 *
 * <p>The console reads are release-addressed
 * ({@code app:feedback:read} through the application-to-owner hop,
 * the permission target of every route): the listing narrows
 * conjunctively by type, status, and submitter tenant (AC4), standard
 * limit/cursor pagination. SECURITY_ISSUE reports carry restricted
 * visibility (AC6): they are invisible — in the listing AND at the
 * detail — to every reader except the owning organization's
 * administrators, its security reviewers, and the platform
 * administrator's audited override; the concealment is the genuine
 * miss, byte-for-byte.
 */
@Service
public class FeedbackService {

    /** Newest first: creation descending, id descending as the deterministic tiebreak. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    /** The description's length bound (free text; bounded, and never echoed into audit or events). */
    private static final int MAX_DESCRIPTION_LENGTH = 8000;

    /** The reported device model's bound (V35's column width). */
    private static final int MAX_DEVICE_MODEL_LENGTH = 256;

    /** The reported OS's bound (V35's column width). */
    private static final int MAX_OS_LENGTH = 128;

    /** The reported file name's bound (V35's column width). */
    private static final int MAX_FILE_NAME_LENGTH = 256;

    /** The six-type vocabulary the validation errors quote (AC1). */
    private static final String TYPE_VOCABULARY = "BUG, FEEDBACK, CRASH, FEATURE_REQUEST, UX_ISSUE, SECURITY_ISSUE";

    /** The severity vocabulary the triage classification errors quote (S11-02, AC1). */
    private static final String SEVERITY_VOCABULARY = "CRITICAL, MAJOR, MINOR";

    /** The resolution note's length bound (V36's column width). */
    private static final int MAX_RESOLUTION_NOTE_LENGTH = 4000;

    /**
     * The triage state machine (S11-02, AC1): the issue's enforced
     * vocabulary — OPEN (the born NEW state) moves to TRIAGED, TRIAGED
     * answers RESOLVED or REJECTED, and every closed state re-opens
     * to TRIAGED (the AC4 flip the metric follows). DISMISSED keeps
     * its historical rows with no new incoming edge — its re-open is
     * the same TRIAGED door.
     */
    private static final Map<Status, Set<Status>> LEGAL_TRIAGE_EDGES = Map.of(
            Status.OPEN, Set.of(Status.TRIAGED),
            Status.TRIAGED, Set.of(Status.RESOLVED, Status.REJECTED),
            Status.RESOLVED, Set.of(Status.TRIAGED),
            Status.REJECTED, Set.of(Status.TRIAGED),
            Status.DISMISSED, Set.of(Status.TRIAGED));

    /** The extension to category+content-type map (AC3): png/jpg/mp4/txt/log, nothing else. */
    private static final Map<String, AttachmentKind> ATTACHMENT_KINDS = Map.of(
            "png", new AttachmentKind(Category.SCREENSHOT, "image/png"),
            "jpg", new AttachmentKind(Category.SCREENSHOT, "image/jpeg"),
            "jpeg", new AttachmentKind(Category.SCREENSHOT, "image/jpeg"),
            "mp4", new AttachmentKind(Category.VIDEO, "video/mp4"),
            "txt", new AttachmentKind(Category.LOG, "text/plain"),
            "log", new AttachmentKind(Category.LOG, "text/plain"));

    private final FeedbackRepository feedback;

    private final FeedbackAttachmentRepository attachments;

    private final FeedbackAttachmentStore store;

    private final FeedbackStorageProperties limits;

    private final ReleaseAccess releases;

    private final ApplicationAccess applications;

    private final ArtifactAccess artifacts;

    private final TesterAccess testers;

    private final TenantTesterMembershipAccess memberships;

    private final OrganizationAccess organizations;

    private final TenantScopeExecutor scopeExecutor;

    private final AuditEventPublisher audit;

    private final ApplicationEventPublisher events;

    private final Clock clock;

    private final TraceAttributes trace;

    FeedbackService(
            FeedbackRepository feedback,
            FeedbackAttachmentRepository attachments,
            FeedbackAttachmentStore store,
            FeedbackStorageProperties limits,
            ReleaseAccess releases,
            ApplicationAccess applications,
            ArtifactAccess artifacts,
            TesterAccess testers,
            TenantTesterMembershipAccess memberships,
            OrganizationAccess organizations,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit,
            ApplicationEventPublisher events,
            Clock clock,
            TraceAttributes trace) {
        this.feedback = feedback;
        this.attachments = attachments;
        this.store = store;
        this.limits = limits;
        this.releases = releases;
        this.applications = applications;
        this.artifacts = artifacts;
        this.testers = testers;
        this.memberships = memberships;
        this.organizations = organizations;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // Submission (AC1, AC2, AC3, AC5)
    // -------------------------------------------------------------------------

    /**
     * Submits one report (AC1/AC2): validations refuse before any
     * write — the type must be one of the six, the description
     * non-empty and bounded, the device/OS free text bounded, the
     * release link must resolve for the caller's own membership, the
     * optional artifact must be a READY build of the release's
     * version, and every attachment inside the configured limits —
     * then the report, its attachments, the audit event, and the
     * domain event land in one transaction. Returns the stored view
     * (201), the server-attached context included.
     */
    @Transactional
    public FeedbackView submit(Object principal, SubmissionCommand command, List<MultipartFile> files) {
        UUID actorId = subjectOf(principal, "feedback");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        Type type = validateType(command.type(), fieldErrors);
        String description = validateDescription(command.description(), fieldErrors);
        String deviceModel =
                validateOptionalText("device_model", command.deviceModel(), MAX_DEVICE_MODEL_LENGTH, fieldErrors);
        String os = validateOptionalText("os", command.os(), MAX_OS_LENGTH, fieldErrors);
        if (!fieldErrors.isEmpty()) {
            throw new FeedbackException(
                    FeedbackException.Code.INVALID_REQUEST, "the submission has invalid fields", fieldErrors);
        }
        Map<String, String> attachmentErrors = new LinkedHashMap<>();
        List<ValidatedAttachment> validated = validateAttachments(files, attachmentErrors);
        if (!attachmentErrors.isEmpty()) {
            throw new FeedbackException(
                    FeedbackException.Code.ATTACHMENT_REJECTED,
                    "the submission's attachments are rejected",
                    attachmentErrors);
        }

        // The subject's tester record: a caller with none (a console
        // persona) is the concealed miss, never a 500.
        UUID testerId = testers.testerIdOfUser(actorId).orElseThrow(FeedbackService::concealedSupplier);

        // The release link (AC2): the submission's entire standing. An
        // unknown application/version/release, a release that does not
        // serve the named tenant, and a caller without an effective
        // membership there are the same concealed miss.
        if (command.tenantId() == null || !memberships.isEffectivelyActive(command.tenantId(), testerId)) {
            throw concealedSupplier();
        }
        ReleaseFacts facts = releases.submissionFactsOf(
                        command.applicationId(), command.versionId(), command.tenantId())
                .orElseThrow(FeedbackService::concealedSupplier);

        // The optional build context: a READY artifact of the release's version.
        UUID resolvedArtifactId = null;
        if (command.artifactId() != null) {
            ArtifactAccess.ArtifactState artifact =
                    artifacts.stateOf(command.artifactId()).orElseThrow(FeedbackService::artifactMissSupplier);
            if (!facts.versionId().equals(artifact.versionId()) || !"READY".equals(artifact.status())) {
                throw artifactMissSupplier();
            }
            resolvedArtifactId = artifact.artifactId();
        }
        final UUID artifactId = resolvedArtifactId;

        FeedbackView view = scopeExecutor.inScope(command.tenantId(), actorId, () -> {
            FeedbackEntity saved = feedback.save(new FeedbackEntity(
                    facts.releaseId(),
                    artifactId,
                    testerId,
                    facts.organizationId(),
                    command.tenantId(),
                    facts.applicationId(),
                    facts.versionId(),
                    deviceModel,
                    os,
                    type,
                    description));
            List<AttachmentView> attachmentViews = new ArrayList<>(validated.size());
            for (ValidatedAttachment attachment : validated) {
                UUID attachmentId = UUID.randomUUID();
                String storageKey = facts.organizationId() + "/" + command.tenantId() + "/feedback/" + saved.getId()
                        + "/" + attachmentId;
                String checksum = store.put(storageKey, attachment.content(), attachment.contentType());
                FeedbackAttachmentEntity stored = attachments.save(new FeedbackAttachmentEntity(
                        saved.getId(),
                        attachment.fileName(),
                        attachment.category(),
                        attachment.contentType(),
                        attachment.size(),
                        checksum,
                        storageKey));
                attachmentViews.add(new AttachmentView(
                        stored.getId(),
                        stored.getFileName(),
                        stored.getCategory().name(),
                        stored.getSizeBytes()));
            }
            return viewOf(saved, facts.releaseRef(), attachmentViews);
        });

        trace.application(facts.applicationId());
        Map<String, String> auditDetail = new LinkedHashMap<>();
        auditDetail.put("releaseRef", facts.releaseRef());
        auditDetail.put("type", type.name());
        auditDetail.put("attachments", String.valueOf(validated.size()));
        if (artifactId != null) {
            auditDetail.put("artifactId", artifactId.toString());
        }
        if (deviceModel != null) {
            auditDetail.put("deviceModel", deviceModel);
        }
        if (os != null) {
            auditDetail.put("os", os);
        }
        audit.publish(AuditEvent.builder(AuditEventType.FEEDBACK_RECEIVED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(facts.organizationId())
                .tenantId(command.tenantId())
                .applicationId(facts.applicationId())
                .versionId(facts.versionId())
                .artifactId(artifactId)
                .success(true)
                .detail(auditDetail)
                .build());
        events.publishEvent(new FeedbackReceivedEvent(
                view.id(),
                facts.releaseId(),
                facts.releaseRef(),
                facts.applicationId(),
                facts.versionId(),
                artifactId,
                facts.organizationId(),
                command.tenantId(),
                testerId,
                type.name(),
                validated.size(),
                Instant.now(clock)));
        return view;
    }

    // -------------------------------------------------------------------------
    // Console reads (AC4, AC6)
    // -------------------------------------------------------------------------

    /**
     * The release's feedback listing (AC4): conjunctive filters by
     * type, status, severity, and submitter tenant over the
     * release-grain scope, newest first, standard limit/cursor
     * pagination. SECURITY_ISSUE rows are filtered out for every
     * reader below the restricted visibility's bar (AC6) — they do
     * not even paginate.
     */
    @Transactional(readOnly = true)
    public PageResponse<FeedbackSummaryView> list(
            Object principal,
            UUID applicationId,
            UUID releaseId,
            String type,
            String status,
            String severity,
            UUID tenantId,
            ListQuery query) {
        UUID actorId = subjectOf(principal, "feedback");
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        Type typeFilter = type == null || type.isBlank() ? null : parseVocabulary(Type.class, type, "type");
        Status statusFilter =
                status == null || status.isBlank() ? null : parseVocabulary(Status.class, status, "status");
        Severity severityFilter =
                severity == null || severity.isBlank() ? null : parseVocabulary(Severity.class, severity, "severity");
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            boolean restrictedReader = restrictedReader(actorId, facts.organizationId());
            Specification<FeedbackEntity> filter = releaseIs(facts.releaseId());
            if (typeFilter != null) {
                filter = filter.and(typeIs(typeFilter));
            }
            if (statusFilter != null) {
                filter = filter.and(statusIs(statusFilter));
            }
            if (severityFilter != null) {
                filter = filter.and(severityIs(severityFilter));
            }
            if (tenantId != null) {
                filter = filter.and(submitterTenantIs(tenantId));
            }
            if (!restrictedReader) {
                filter = filter.and(typeIsNot(Type.SECURITY_ISSUE));
            }
            Page<FeedbackEntity> page =
                    feedback.findAll(filter, new CursorPageable(query.offset(), query.limit(), NEWEST_FIRST));
            List<FeedbackSummaryView> items =
                    page.getContent().stream().map(FeedbackService::summaryOf).toList();
            return PageResponse.of(items, page.hasNext(), query.offset() + query.limit());
        });
    }

    /**
     * The report's detail (AC4's read leg): the full record with its
     * attachments. A SECURITY_ISSUE report for a reader below the
     * restricted bar conceals as the genuine miss (AC6).
     */
    @Transactional(readOnly = true)
    public FeedbackView get(Object principal, UUID applicationId, UUID releaseId, UUID feedbackId) {
        UUID actorId = subjectOf(principal, "feedback");
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            FeedbackEntity row = feedback.findByIdAndReleaseId(feedbackId, facts.releaseId())
                    .orElseThrow(FeedbackService::missSupplier);
            if (row.getType() == Type.SECURITY_ISSUE && !restrictedReader(actorId, facts.organizationId())) {
                throw missSupplier();
            }
            List<AttachmentView> views = attachments.findByFeedbackIdOrderByFileNameAsc(row.getId()).stream()
                    .map(FeedbackService::attachmentViewOf)
                    .toList();
            return viewOf(row, facts.releaseRef(), views);
        });
    }

    /**
     * Streams one attachment (AC3's read leg): the same authorization
     * as the report's detail — parent release resolved and scoped,
     * the security-issue restriction enforced — then the
     * post-authorization egress stream.
     */
    @Transactional(readOnly = true)
    public AttachmentContent download(
            Object principal, UUID applicationId, UUID releaseId, UUID feedbackId, UUID attachmentId) {
        UUID actorId = subjectOf(principal, "feedback");
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            FeedbackEntity row = feedback.findByIdAndReleaseId(feedbackId, facts.releaseId())
                    .orElseThrow(FeedbackService::missSupplier);
            if (row.getType() == Type.SECURITY_ISSUE && !restrictedReader(actorId, facts.organizationId())) {
                throw missSupplier();
            }
            FeedbackAttachmentEntity attachment = attachments
                    .findByIdAndFeedbackId(attachmentId, row.getId())
                    .orElseThrow(FeedbackService::missSupplier);
            return new AttachmentContent(
                    store.open(attachment.getStorageKey()), attachment.getContentType(), attachment.getFileName());
        });
    }

    // -------------------------------------------------------------------------
    // Triage (S11-02: severity, assignment, lifecycle — AC1) and the
    // release-dashboard rollup (AC2)
    // -------------------------------------------------------------------------

    /**
     * Classifies the report (AC1): the severity vocabulary is QA
     * authority over a report the workflow can still move — a closed
     * report refuses the call until re-opened (its classification is
     * history). The crossing to CRITICAL publishes {@link
     * FeedbackMarkedCriticalEvent} beside the audit (AC5's fact), and
     * the open-critical-bugs metric counts the report from the same
     * committed write.
     */
    @Transactional
    public FeedbackView setSeverity(
            Object principal, UUID applicationId, UUID releaseId, UUID feedbackId, String severity) {
        UUID actorId = subjectOf(principal, "feedback");
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            FeedbackEntity row = triageTarget(actorId, facts, feedbackId);
            Severity next = validateSeverity(severity);
            if (!FeedbackEntity.UNRESOLVED.contains(row.getStatus())) {
                throw new FeedbackException(
                        FeedbackException.Code.FEEDBACK_TRANSITION_INVALID,
                        "a " + row.getStatus() + " report's severity is fixed; re-open it first",
                        Map.of("severity", "the report is " + row.getStatus() + "; re-open it before reclassifying"));
            }
            Severity previous = row.applySeverity(next);
            feedback.saveAndFlush(row);
            Map<String, String> severityDetail = new LinkedHashMap<>();
            severityDetail.put("releaseRef", facts.releaseRef());
            if (previous != null) {
                severityDetail.put("from", previous.name());
            }
            severityDetail.put("to", next.name());
            audit.publish(AuditEvent.builder(AuditEventType.FEEDBACK_SEVERITY_CHANGED)
                    .occurredAt(Instant.now(clock))
                    .userId(actorId)
                    .orgId(facts.organizationId())
                    .tenantId(facts.tenantId())
                    .applicationId(facts.applicationId())
                    .success(true)
                    .detail(severityDetail)
                    .build());
            if (next == Severity.CRITICAL) {
                events.publishEvent(new FeedbackMarkedCriticalEvent(
                        row.getId(),
                        facts.releaseId(),
                        facts.releaseRef(),
                        facts.applicationId(),
                        facts.organizationId(),
                        row.getSubmitterTenantId(),
                        row.getTesterId(),
                        row.getType().name(),
                        Instant.now(clock)));
            }
            return viewOf(row, facts.releaseRef(), attachmentViewsOf(row));
        });
    }

    /**
     * Assigns the member who works the report (AC1), or clears the
     * assignment with a null id. The candidate must hold membership in
     * the owning organization — the same directory the console's
     * roster renders from; nobody else can be named.
     */
    @Transactional
    public FeedbackView assign(
            Object principal, UUID applicationId, UUID releaseId, UUID feedbackId, UUID assigneeUserId) {
        UUID actorId = subjectOf(principal, "feedback");
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            FeedbackEntity row = triageTarget(actorId, facts, feedbackId);
            if (assigneeUserId != null
                    && !organizations.activeOrganizationRoles(assigneeUserId).containsKey(facts.organizationId())) {
                throw new FeedbackException(
                        FeedbackException.Code.ASSIGNEE_NOT_ELIGIBLE,
                        "the assignee is not a member of the owning organization",
                        Map.of("assigneeUserId", "not a member of the release's organization"));
            }
            UUID previous = row.applyAssignee(assigneeUserId);
            feedback.saveAndFlush(row);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("releaseRef", facts.releaseRef());
            detail.put("assignee", assigneeUserId == null ? "cleared" : assigneeUserId.toString());
            if (previous != null) {
                detail.put("previously", previous.toString());
            }
            audit.publish(AuditEvent.builder(AuditEventType.FEEDBACK_ASSIGNEE_CHANGED)
                    .occurredAt(Instant.now(clock))
                    .userId(actorId)
                    .orgId(facts.organizationId())
                    .tenantId(facts.tenantId())
                    .applicationId(facts.applicationId())
                    .success(true)
                    .detail(detail)
                    .build());
            return viewOf(row, facts.releaseRef(), attachmentViewsOf(row));
        });
    }

    /**
     * Moves the report through its lifecycle (AC1): the enforced edges
     * of the triage state machine — OPEN to TRIAGED, TRIAGED to
     * RESOLVED or REJECTED (the note mandatory: a closed report
     * explains itself, the release-transition discipline), and every
     * closed state back to TRIAGED (the AC4 re-open whose flip the
     * metric follows on the next evaluation). Every crossing is
     * audited with its actor and note.
     */
    @Transactional
    public FeedbackView transition(
            Object principal, UUID applicationId, UUID releaseId, UUID feedbackId, TransitionCommand command) {
        UUID actorId = subjectOf(principal, "feedback");
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            FeedbackEntity row = triageTarget(actorId, facts, feedbackId);
            Status to = parseVocabulary(Status.class, command.to(), "to");
            Set<Status> legal = LEGAL_TRIAGE_EDGES.get(row.getStatus());
            Map<String, String> errors = new LinkedHashMap<>();
            if (legal == null || !legal.contains(to)) {
                errors.put(
                        "to",
                        "a " + row.getStatus() + " report moves to " + (legal == null ? Set.of() : legal) + " only");
            }
            String note = null;
            if (command.note() != null && !command.note().isBlank()) {
                note = command.note().trim();
                if (note.length() > MAX_RESOLUTION_NOTE_LENGTH) {
                    errors.put("note", "note must be at most " + MAX_RESOLUTION_NOTE_LENGTH + " characters");
                }
            }
            if ((to == Status.RESOLVED || to == Status.REJECTED) && note == null) {
                errors.put(
                        "note", "a resolution note is required to " + to.name().toLowerCase(Locale.ROOT));
            }
            if (!errors.isEmpty()) {
                throw new FeedbackException(
                        FeedbackException.Code.FEEDBACK_TRANSITION_INVALID, "the triage transition is refused", errors);
            }
            Status from = row.applyTransition(to, note);
            feedback.saveAndFlush(row);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("releaseRef", facts.releaseRef());
            detail.put("from", from.name());
            detail.put("to", to.name());
            if (note != null) {
                detail.put("note", note);
            }
            audit.publish(AuditEvent.builder(AuditEventType.FEEDBACK_STATUS_TRANSITIONED)
                    .occurredAt(Instant.now(clock))
                    .userId(actorId)
                    .orgId(facts.organizationId())
                    .tenantId(facts.tenantId())
                    .applicationId(facts.applicationId())
                    .success(true)
                    .detail(detail)
                    .build());
            return viewOf(row, facts.releaseRef(), attachmentViewsOf(row));
        });
    }

    /**
     * The release-dashboard Feedback block (AC2, PRD 32's "14 reports
     * . 1 critical"): counts by severity and status beside the
     * open-critical count the gate metric answers — the rollup folded
     * from the release grain with the reader's visibility applied, so
     * a reader below the security-issue bar sees counts of exactly
     * the rows the listing would show them.
     */
    @Transactional(readOnly = true)
    public FeedbackRollupView rollup(Object principal, UUID applicationId, UUID releaseId) {
        UUID actorId = subjectOf(principal, "feedback");
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            boolean restrictedReader = restrictedReader(actorId, facts.organizationId());
            long total = 0;
            long openCritical = 0;
            Map<String, Long> bySeverity = new LinkedHashMap<>();
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (Object[] grouped : feedback.rollupByReleaseId(facts.releaseId())) {
                Severity severity = (Severity) grouped[0];
                Status status = (Status) grouped[1];
                Type type = (Type) grouped[2];
                long count = ((Number) grouped[3]).longValue();
                if (!restrictedReader && type == Type.SECURITY_ISSUE) {
                    continue;
                }
                total += count;
                String severityKey = severity == null ? "UNTRIAGED" : severity.name();
                bySeverity.merge(severityKey, count, Long::sum);
                byStatus.merge(status.name(), count, Long::sum);
                if (severity == Severity.CRITICAL && FeedbackEntity.UNRESOLVED.contains(status)) {
                    openCritical += count;
                }
            }
            return new FeedbackRollupView(facts.releaseId(), total, openCritical, bySeverity, byStatus);
        });
    }

    /**
     * The triage action's addressed row: the detail read's own
     * resolution and concealment — an unknown, foreign, or
     * security-restricted report is the genuine miss for a caller
     * below the bar, so the ACTION refuses exactly as the read does
     * (never a 500, never a hint the row exists).
     */
    private FeedbackEntity triageTarget(UUID actorId, ReleaseFacts facts, UUID feedbackId) {
        FeedbackEntity row =
                feedback.findByIdAndReleaseId(feedbackId, facts.releaseId()).orElseThrow(FeedbackService::missSupplier);
        if (row.getType() == Type.SECURITY_ISSUE && !restrictedReader(actorId, facts.organizationId())) {
            throw missSupplier();
        }
        return row;
    }

    private Severity validateSeverity(String raw) {
        Map<String, String> errors = new LinkedHashMap<>();
        Severity severity = null;
        if (raw == null || raw.isBlank()) {
            errors.put("severity", "severity is required (" + SEVERITY_VOCABULARY + ")");
        } else {
            try {
                severity = Severity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.put("severity", "unknown severity '" + raw.trim() + "'; must be one of " + SEVERITY_VOCABULARY);
            }
        }
        if (!errors.isEmpty()) {
            throw new FeedbackException(
                    FeedbackException.Code.INVALID_REQUEST, "the classification has invalid fields", errors);
        }
        return severity;
    }

    private List<AttachmentView> attachmentViewsOf(FeedbackEntity row) {
        return attachments.findByFeedbackIdOrderByFileNameAsc(row.getId()).stream()
                .map(FeedbackService::attachmentViewOf)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Views and commands
    // -------------------------------------------------------------------------

    /** The submission contract (AC1): the tester-typed fields only — every identity field is server-attached. */
    public record SubmissionCommand(
            UUID applicationId,
            UUID versionId,
            UUID tenantId,
            UUID artifactId,
            String type,
            String description,
            String deviceModel,
            String os) {}

    /** The stored report: the server-attached context in full (the response proves the client never supplied it). */
    public record FeedbackView(
            UUID id,
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            UUID organizationId,
            UUID submitterTenantId,
            UUID testerId,
            String deviceModel,
            String os,
            String type,
            String status,
            String severity,
            UUID assigneeUserId,
            String resolutionNote,
            String description,
            Instant createdAt,
            Instant updatedAt,
            List<AttachmentView> attachments) {}

    /** The list item: the triage surface's scan row (the detail carries the description and attachments). */
    public record FeedbackSummaryView(
            UUID id,
            UUID releaseId,
            UUID submitterTenantId,
            UUID testerId,
            String type,
            String status,
            String severity,
            UUID assigneeUserId,
            Instant createdAt) {}

    /**
     * The release-dashboard rollup (AC2, PRD 32): the total beside the
     * open-critical count the gate metric answers, and the per-axis
     * folds — severity (UNTRIAGED for the not-yet-classified) and
     * status. Counts cover exactly the rows the calling reader can
     * see.
     */
    public record FeedbackRollupView(
            UUID releaseId, long total, long openCritical, Map<String, Long> bySeverity, Map<String, Long> byStatus) {}

    /** One triage lifecycle crossing (AC1): the target status and the mandatory note of the closing edges. */
    public record TransitionCommand(String to, String note) {}

    /** One attachment's metadata. */
    public record AttachmentView(UUID id, String fileName, String category, long sizeBytes) {}

    /** The download route's answer: the egress stream with its serving facts. */
    public record AttachmentContent(InputStream content, String contentType, String fileName) {}

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private Type validateType(String raw, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.put("type", "type is required (" + TYPE_VOCABULARY + ")");
            return null;
        }
        try {
            return Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.put("type", "unknown type '" + raw.trim() + "'; must be one of " + TYPE_VOCABULARY);
            return null;
        }
    }

    private String validateDescription(String raw, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.put("description", "description is required");
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            errors.put("description", "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
            return null;
        }
        return trimmed;
    }

    private String validateOptionalText(String field, String raw, int maxLength, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            errors.put(field, field + " must be at most " + maxLength + " characters");
            return null;
        }
        return trimmed;
    }

    /**
     * The attachment gate (AC3): count, extension (png/jpg/mp4/txt/log
     * — the extension resolves the category and the SERVED content
     * type, never the client's declaration), per-kind size, duplicate
     * names within one report, and the file-name length. Offenses key
     * the {@code errors} member by file name, one pass, before any
     * write.
     */
    private List<ValidatedAttachment> validateAttachments(List<MultipartFile> files, Map<String, String> fieldErrors) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (files.size() > limits.maxAttachmentsPerReport()) {
            fieldErrors.put("attachments", "at most " + limits.maxAttachmentsPerReport() + " attachments per report");
            return List.of();
        }
        Map<String, AttachmentKind> kindsByFile = new LinkedHashMap<>();
        List<ValidatedAttachment> validated = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            String original = file.getOriginalFilename();
            String name = original == null ? "" : original.trim();
            if (name.isEmpty()) {
                fieldErrors.put("attachments", "every attachment needs a file name");
                continue;
            }
            String normalized = name.replace('\\', '/');
            String leaf = normalized.substring(normalized.lastIndexOf('/') + 1);
            if (leaf.length() > MAX_FILE_NAME_LENGTH) {
                fieldErrors.put(leaf, "file name must be at most " + MAX_FILE_NAME_LENGTH + " characters");
                continue;
            }
            String extension = leaf.contains(".")
                    ? leaf.substring(leaf.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                    : "";
            AttachmentKind kind = ATTACHMENT_KINDS.get(extension);
            if (kind == null) {
                fieldErrors.put(leaf, "allowed attachment types are png, jpg, mp4, txt, log");
                continue;
            }
            if (kindsByFile.containsKey(leaf)) {
                fieldErrors.put(leaf, "duplicate file name within one report");
                continue;
            }
            long maxBytes = switch (kind.category()) {
                case SCREENSHOT -> limits.maxScreenshotBytes();
                case VIDEO -> limits.maxVideoBytes();
                case LOG -> limits.maxLogBytes();
            };
            if (file.getSize() > maxBytes) {
                fieldErrors.put(
                        leaf,
                        "file exceeds the "
                                + kind.category().name().toLowerCase(Locale.ROOT)
                                + " limit of "
                                + maxBytes
                                + " bytes");
                continue;
            }
            kindsByFile.put(leaf, kind);
            try {
                validated.add(new ValidatedAttachment(leaf, kind, file.getSize(), file.getInputStream()));
            } catch (IOException e) {
                fieldErrors.put(leaf, "the uploaded part could not be read");
            }
        }
        return validated;
    }

    /** One accepted file part: everything the store needs, nothing the client declared beyond the bytes. */
    private record ValidatedAttachment(String fileName, AttachmentKind kind, long size, InputStream content) {

        Category category() {
            return kind.category();
        }

        String contentType() {
            return kind.contentType();
        }
    }

    /** The extension-resolved serving facts of one attachment kind. */
    private record AttachmentKind(Category category, String contentType) {}

    // -------------------------------------------------------------------------
    // Scope, subject, and concealment helpers
    // -------------------------------------------------------------------------

    /** The caller's user id from either principal shape (the decision-API routes' resolution). */
    private static UUID subjectOf(Object principal, String missGrain) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new ConcealedResourceAccessDeniedException(missGrain);
    }

    /** The submission surface's single concealment (AC2): every failed link is this same miss. */
    private static FeedbackException concealedSupplier() {
        return new FeedbackException(
                FeedbackException.Code.FEEDBACK_RELEASE_NOT_FOUND, "the release link did not resolve for this caller");
    }

    private static FeedbackException artifactMissSupplier() {
        return new FeedbackException(
                FeedbackException.Code.ARTIFACT_NOT_FOUND, "the artifact did not resolve for this release");
    }

    private static FeedbackException missSupplier() {
        return new FeedbackException(FeedbackException.Code.FEEDBACK_NOT_FOUND, "the report did not resolve");
    }

    private ApplicationAccess.ApplicationProfile profileOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .orElseThrow(() -> new ConcealedResourceAccessDeniedException("application"));
    }

    /**
     * The sanctioned scope of the application's owning dimension (the
     * release surface's own helper, verbatim): a tenant-owned
     * application binds its tenant, a shared one its organization.
     */
    private <T> T inScope(ApplicationAccess.ApplicationProfile application, UUID actorId, Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    /** The parent resolution inside the bound scope: unknown, foreign, and mismatched-application ids are one miss. */
    private ReleaseFacts factsOf(UUID applicationId, UUID releaseId) {
        ReleaseFacts facts = releases.factsOf(releaseId).orElseThrow(FeedbackService::missSupplier);
        if (!applicationId.equals(facts.applicationId())) {
            throw missSupplier();
        }
        return facts;
    }

    /**
     * The restricted-visibility bar (AC6): the owning organization's
     * administrators and security readers pass; so does the platform
     * administrator's audited override (the seam's cross-tenant
     * standing). Everyone else — including holders of the general
     * {@code app:feedback:read} — does not see SECURITY_ISSUE rows.
     */
    private boolean restrictedReader(UUID actorId, UUID organizationId) {
        if (TenantContext.current().map(TenantContext::crossTenantOverride).orElse(false)) {
            return true;
        }
        OrgRole role = organizations.activeOrganizationRoles(actorId).get(organizationId);
        return role == OrgRole.ORG_ADMIN || role == OrgRole.SECURITY_REVIEWER;
    }

    /** An unknown filter token is a validation refusal (the release listing's status precedent), never a concealment. */
    private static <E extends Enum<E>> E parseVocabulary(Class<E> vocabulary, String raw, String field) {
        try {
            return Enum.valueOf(vocabulary, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FeedbackException(
                    FeedbackException.Code.INVALID_REQUEST,
                    "unknown " + field + " '" + raw.trim() + "'",
                    Map.of(field, "unknown " + field + " '" + raw.trim() + "'"));
        }
    }

    private static FeedbackSummaryView summaryOf(FeedbackEntity row) {
        return new FeedbackSummaryView(
                row.getId(),
                row.getReleaseId(),
                row.getSubmitterTenantId(),
                row.getTesterId(),
                row.getType().name(),
                row.getStatus().name(),
                row.getSeverity() == null ? null : row.getSeverity().name(),
                row.getAssigneeUserId(),
                row.getCreatedAt());
    }

    private static AttachmentView attachmentViewOf(FeedbackAttachmentEntity attachment) {
        return new AttachmentView(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getCategory().name(),
                attachment.getSizeBytes());
    }

    private static FeedbackView viewOf(FeedbackEntity row, String releaseRef, List<AttachmentView> attachmentViews) {
        return new FeedbackView(
                row.getId(),
                row.getReleaseId(),
                releaseRef,
                row.getApplicationId(),
                row.getVersionId(),
                row.getArtifactId(),
                row.getOrganizationId(),
                row.getSubmitterTenantId(),
                row.getTesterId(),
                row.getDeviceModel(),
                row.getOs(),
                row.getType().name(),
                row.getStatus().name(),
                row.getSeverity() == null ? null : row.getSeverity().name(),
                row.getAssigneeUserId(),
                row.getResolutionNote(),
                row.getDescription(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                attachmentViews);
    }

    // -------------------------------------------------------------------------
    // Specifications (the listing's conjunctive filters)
    // -------------------------------------------------------------------------

    private static Specification<FeedbackEntity> releaseIs(UUID releaseId) {
        return (root, query, builder) -> builder.equal(root.get("releaseId"), releaseId);
    }

    private static Specification<FeedbackEntity> typeIs(Type type) {
        return (root, query, builder) -> builder.equal(root.get("type"), type);
    }

    private static Specification<FeedbackEntity> typeIsNot(Type type) {
        return (root, query, builder) -> builder.notEqual(root.get("type"), type);
    }

    private static Specification<FeedbackEntity> statusIs(Status status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    private static Specification<FeedbackEntity> severityIs(Severity severity) {
        return (root, query, builder) -> builder.equal(root.get("severity"), severity);
    }

    private static Specification<FeedbackEntity> submitterTenantIs(UUID tenantId) {
        return (root, query, builder) -> builder.equal(root.get("submitterTenantId"), tenantId);
    }
}
