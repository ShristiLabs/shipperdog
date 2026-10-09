package com.shipperdog.api.audit;

/**
 * Canonical audit event vocabulary. Values are never removed once used;
 * new kinds are appended (audit records are immutable history, PRD 27).
 */
public enum AuditEventType {
    USER_REGISTERED,
    INVITATION_CREATED,
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    USER_LOCKED_OUT,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    TOKEN_ISSUED,
    TOKEN_REFRESHED,
    TOKEN_REVOKED,
    TOKEN_REUSE_DETECTED,
    PAT_CREATED,
    PAT_REVOKED,
    ORGANIZATION_CREATED,
    ORGANIZATION_UPDATED,
    ORG_MEMBER_ADDED,
    ORG_IDP_CONFIGURED,
    ORG_MEMBER_ROLE_CHANGED,
    ORG_MEMBER_REMOVED,
    TENANT_CREATED,
    TENANT_UPDATED,
    TENANT_MEMBER_ADDED,
    TENANT_MEMBER_ROLE_CHANGED,
    TENANT_MEMBER_STATUS_CHANGED,
    TENANT_MEMBER_REMOVED,
    CROSS_TENANT_ACCESS,
    ACCESS_DENIED,
    APPLICATION_CREATED,
    APPLICATION_UPDATED,
    APPLICATION_SCOPE_CHANGED,
    APPLICATION_TENANT_AUTHORIZED,
    APPLICATION_TENANT_DEAUTHORIZED,
    VERSION_CREATED,
    VERSION_TAGS_CHANGED,
    VERSION_ARCHIVED,
    VERSION_DISTRIBUTION_REVOKED,
    ARTIFACT_UPLOAD_SESSION_CREATED,
    ARTIFACT_UPLOADED,
    ARTIFACT_METADATA_EXTRACTED,
    ARTIFACT_VALIDATED,
    ARTIFACT_REVALIDATION_REQUESTED,
    /** One ALLOWED download issuance (S8-04, FR-014): who/what/artifact/result beside the engine's own decision event. */
    DOWNLOAD_ISSUED,
    TESTER_INVITED,
    TESTER_ACTIVATED,
    TESTER_STATUS_CHANGED,
    TESTER_DEACTIVATED,
    TESTER_REACTIVATED,
    TESTER_GROUP_CREATED,
    TESTER_GROUP_UPDATED,
    TESTER_GROUP_DELETED,
    TESTER_GROUP_MEMBER_ADDED,
    TESTER_GROUP_MEMBER_REMOVED,
    TENANT_TESTER_MEMBERSHIP_CREATED,
    TENANT_TESTER_MEMBERSHIP_STATUS_CHANGED,
    TENANT_TESTER_MEMBERSHIP_REMOVED,
    AUTHORIZATION_DECISION,
    AUTHORIZATION_GRANT_CREATED,
    AUTHORIZATION_GRANT_EXPIRY_CHANGED,
    /**
     * The S7-02 removal primitive's event; no writer emits it since
     * S7-04 (issue #33) turned removal into revocation — the constant
     * stays for the historical rows the audit store already holds
     * (values are never removed once used).
     */
    @Deprecated
    AUTHORIZATION_GRANT_DELETED,
    AUTHORIZATION_GRANT_REVOKED,
    /** A DRAFT release created with its full target set (S9-01, FR-015): ring, per-platform artifacts, targets. */
    RELEASE_CREATED,
    /**
     * One committed PRD 17 transition (S9-02, FR-024): releaseRef, fromStatus, toStatus, and the
     * mandatory reason of failure/rollback crossings; one event per real transition, never a repeat.
     */
    RELEASE_TRANSITIONED,
    /**
     * The release manifest frozen at the APPROVED crossing (S9-03, FR-024, PRD 16): releaseRef and the
     * manifest's SHA-256 content hash — the digest that pins WHAT the release shipped, recorded once,
     * at freeze time; the document itself never changes, so neither does this event's meaning.
     */
    RELEASE_MANIFEST_FROZEN,
    /**
     * A custom environment created (S9-04, FR-025): code, name, position, and the name-only channel
     * and policy bindings when present. The seeded defaults of a new organization are part of its
     * creation and carry no separate event.
     */
    ENVIRONMENT_CREATED,
    /** One environment's mutable facts changed (S9-04): the changed fields and their new values. */
    ENVIRONMENT_UPDATED,
    /** An environment's lifecycle crossing (S9-04): from, to, and the optional reason. */
    ENVIRONMENT_STATUS_CHANGED,
    /**
     * One configuration write appended to an environment's ledger (S9-04, FR-025): the version and the
     * per-key movement (added, removed, changed). History lives in the ledger; this event is the who
     * and the when beside it.
     */
    ENVIRONMENT_CONFIG_UPDATED,
    /**
     * An environment's tenant assignment set replaced (S9-04, FR-025/AC4): the tenants added and
     * removed — the allow-list release creation validates targets against.
     */
    ENVIRONMENT_TENANTS_ASSIGNED,
    /**
     * An environment's credential binding set replaced (S9-04, FR-025/AC3): binding names added,
     * removed, and repointed — with their secret REFERENCES, which are the audit fact; material never
     * reaches the platform, so it can never reach an audit event.
     */
    ENVIRONMENT_CREDENTIALS_BOUND,
    /**
     * One credential resolution probe (S9-04, FR-025/AC5): the binding's name and reference and
     * whether the resolver answered — probing credentials is a security-relevant act, audited even
     * though it mutates nothing.
     */
    ENVIRONMENT_CREDENTIAL_VERIFIED,
    /**
     * An approval workflow definition created (S10-01, FR-016): the name, the ring attachment when
     * present, and the ordered chain — the configurable approval vocabulary an organization defines.
     */
    APPROVAL_WORKFLOW_CREATED,
    /**
     * An approval round instantiated for a release (S10-01, FR-016): the round number, the workflow
     * resolved, the steps instantiated, and the trigger — the APPROVAL_PENDING crossing (round 1) or
     * the rework restart after a rejection (later rounds).
     */
    RELEASE_APPROVAL_ROUND_STARTED,
    /**
     * One approval step signed (S10-01, FR-016, AC6): the release, round, position, approval type,
     * the approver, and the role they signed under — recorded at decision time; roles change later,
     * the record does not.
     */
    RELEASE_APPROVAL_GRANTED,
    /**
     * One approval step rejected (S10-01, FR-016, AC6): the release, round, position, approval type,
     * the approver, and the mandatory reason — the rejection that blocks progression and opens the
     * rework path.
     */
    RELEASE_APPROVAL_REJECTED,
    /**
     * An approval workflow definition edited (S10-02, FR-016): the name, the ring attachment, and
     * the reordered/re-grouped chain — definitions are editable so governance can evolve, while
     * rounds already run keep their frozen per-step copies (later rounds, including rework
     * restarts, instantiate the definition's current content).
     */
    APPROVAL_WORKFLOW_UPDATED,
    /**
     * A gate definition created (S10-03, FR-026): the gate type of the open vocabulary, the required
     * flag, the expected-result config (a coverage threshold, a critical-bug ceiling), and the ring
     * attachment when present — the required-gate catalog an organization defines.
     */
    GATE_DEFINITION_CREATED,
    /** A gate definition edited (S10-03, FR-026): the changed required flag, config, and ring attachment. */
    GATE_DEFINITION_UPDATED,
    /**
     * One gate result recorded (S10-03, FR-026, AC6): the release, the gate type, the outcome —
     * PASSED or FAILED as an external system submitted it, or NO_DATA/PASSED/FAILED as the
     * evaluation resolved it from analytics (the source member says which) — with the evidence URL
     * and the observed value. The submission, pass, and fail events of the AC are this one event's
     * outcome member: one record per report, the append-only history behind the current state.
     */
    RELEASE_GATE_RESULT_RECORDED,
    /**
     * A blocking gate overridden (S10-03, FR-026, AC6): the release, the gate, the state overridden
     * (FAILED or NO_DATA), and the MANDATORY reason — the governed exception FR-026 demands,
     * recorded with the actor; the row keeps reporting the underlying state, the override governs
     * only whether it still blocks.
     */
    RELEASE_GATE_OVERRIDDEN,
    /**
     * A ring created (S10-04, FR-017, AC5): the code, name, position, eligibility rules, and the
     * three references — the moment a rollout stage enters an organization's vocabulary. Audited
     * because ring definitions change who can receive what.
     */
    RING_CREATED,
    /**
     * A ring edited (S10-04, FR-017, AC5): the changed facts only — name, position, eligibility
     * rules, or references. Audited with the same weight as creation: an edited rule is a new
     * boundary on distribution, applied to every future evaluation.
     */
    RING_UPDATED,
    /**
     * A ring deleted (S10-04, FR-017, AC5): the full deleted facts, the last record of a stage
     * that left the vocabulary (references refuse the deletion, so a deleted ring was never
     * load-bearing).
     */
    RING_DELETED,
    /**
     * One committed ring promotion (S10-05, FR-018, PRD 51's RELEASE_PROMOTED): releaseRef, the two
     * rings' codes, the tenants moved (selective promotion's record), and whether the hop carried the
     * release onto PRD 17's PROMOTED crossing (the final ring, all targets) — one event per hop, the
     * append-only promotions timeline beside it.
     */
    RELEASE_PROMOTED,
    /**
     * A promote attempt refused with recorded evidence (S10-05, FR-018, PRD 33): releaseRef, the ring
     * the hop would have entered, and the complete blocker list — each gate, approval, eligibility,
     * and policy refusal named with its evidence. The refused attempt writes its promotions-timeline
     * row and this audit event; the artifacts and manifest the release froze are untouched (INV-008).
     */
    RELEASE_PROMOTION_BLOCKED,
    /**
     * A policy rule authored (S10-06, issue #45, PRD 33): the organization's standing declarative
     * rule created — name, scope, action, condition, position, enabled — audited in full.
     */
    RELEASE_POLICY_RULE_CREATED,
    /**
     * A policy rule edited (S10-06): the rule's meaning end to end revalidated and replaced; the
     * changed facts audited (ring edits change who can receive what; rule edits change what
     * mechanically refuses a promotion — the same governance standing).
     */
    RELEASE_POLICY_RULE_UPDATED,
    /**
     * A policy rule deleted (S10-06): the full deleted facts audited; the hold ledger's references
     * refuse the delete (they are audit evidence), and the evaluation log's name snapshots survive.
     */
    RELEASE_POLICY_RULE_DELETED,
    /**
     * The skip-relaxation knob turned (S10-06): the named rings a promotion may carry beyond the
     * next — the widening of the #44 default posture, a governed act in its own right.
     */
    RELEASE_POLICY_SETTINGS_UPDATED,
    /**
     * One promote attempt's policy evaluation (S10-06, PRD 33): the ring evaluated, the verdict
     * (ALLOWED, BLOCKED, HELD), every fired rule named — the summary beside the evaluation log's
     * per-rule rows (which rule fired, with what evidence).
     */
    RELEASE_POLICY_EVALUATED,
    /**
     * A policy hold acknowledged (S10-06 AC2): the explicit privileged decision to proceed past a
     * fired HOLD_FOR_REVIEW rule — rule, ring, and the mandatory reason — audited with the actor.
     */
    RELEASE_POLICY_HOLD_ACKNOWLEDGED,
    /**
     * A tester's structured feedback landed (S11-01, issue #46, FR-027, PRD 8.13): the submission's
     * complete server-attached context — the release it evaluates (quotable ref), application,
     * version, artifact when named, the submitting tenant and tester, the report type, and the
     * attachment count. The description's content is deliberately absent (PII minimization); the
     * in-process FeedbackReceivedEvent carries the same facts for S12-01's bus to relay.
     */
    FEEDBACK_RECEIVED,
    /**
     * One triage classification recorded (S11-02, issue #47, AC1): the
     * report, the severity replaced and the severity set — CRITICAL
     * beside the crossing that made it critical, the fact the
     * open-critical-bugs metric turns on. The description's content
     * stays absent (the S11-01 PII minimization posture, kept).
     */
    FEEDBACK_SEVERITY_CHANGED,
    /**
     * One triage assignment recorded (S11-02, AC1): the report, the
     * assignee set (or cleared), and the actor — the working queue's
     * own trail.
     */
    FEEDBACK_ASSIGNEE_CHANGED,
    /**
     * One triage crossing recorded (S11-02, AC1): the report, the
     * from/to statuses, and the mandatory note of the closing and
     * re-opening crossings — one event per real transition, never a
     * repeat (the RELEASE_TRANSITIONED discipline). Re-opens are the
     * same event: the from/to pair says which direction the report
     * moved, and the metric flips with it.
     */
    FEEDBACK_STATUS_TRANSITIONED,
    /**
     * A tester device registered (S11-03, issue #48, AC1, FR-028, PRD 8.12):
     * the tenant scope, the owning tester, the platform, and the device id.
     * The identifier is deliberately absent — it is the semi-sensitive
     * fingerprint whose mass disclosure only the export event records, by
     * count, never by value.
     */
    DEVICE_REGISTERED,
    /**
     * A device's descriptive fields updated (S11-03, AC2): the device, the
     * owner, and the fields that moved (model, os_version) — the identity
     * columns (identifier, platform) never move and never appear.
     */
    DEVICE_UPDATED,
    /**
     * The reversible status switch crossed (S11-03, AC2/AC5): ACTIVE to
     * DEACTIVATED parks the device without deleting anything (its feedback
     * links and the tenant's history stay, the INV-007 posture); the
     * from/to pair records the direction.
     */
    DEVICE_STATUS_CHANGED,
    /**
     * The provisioning export performed (S11-03, AC3): the tenant scope,
     * the status slice exported, and the row count — the identifier's mass
     * disclosure is itself the audited act, recorded by count and never by
     * value (the identifier's privacy note).
     */
    DEVICES_EXPORTED,
    /**
     * One SDK events batch accepted (S11-04, issue #49, FR-029): the
     * release it reports against (quotable ref) and the batch's shape —
     * counts by type. Device identifiers are deliberately absent (the
     * standing privacy posture: the identifier never leaves the device
     * module); the events themselves are the analytics facts, not the
     * audit's business.
     */
    ANALYTICS_EVENTS_INGESTED,
    /**
     * One rollup recompute pass (S11-04, issue #49) — the hourly job's
     * lookback or the manual trigger's explicit window: the window and
     * the rows written or refreshed. Idempotent by construction, so a
     * repeated pass over the same window is the same line, not a
     * signal.
     */
    METRIC_ROLLUPS_RECOMPUTED
}
