package com.shipperdog.api.release.internal.policy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Maps the {@code release_policy_evaluation_rules} table (V34, issue
 * #45, S10-06 AC5): one logged outcome per rule the attempt
 * evaluated, in the defined order. The outcome names the standing —
 * NOT_MET, FIRED, NO_DATA (the explicit fail-closed outcome), or
 * ACKNOWLEDGED (a fired HOLD suppressed by a standing acknowledge) —
 * and the detail carries the evidence (the observed values, the
 * missing metric, the overridden gate). {@code ruleId} is LOOSE and
 * {@code ruleName} a snapshot: the log is history, and history
 * survives the rule's later deletion (the hold ledger — living state
 * — is what the delete guard protects instead).
 */
@Entity
@Table(name = "release_policy_evaluation_rules")
public class ReleasePolicyEvaluationRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID evaluationId;

    /** LOOSE by design: history survives the rule's deletion. */
    private UUID ruleId;

    private String ruleName;

    private int rulePosition;

    private String action;

    private String outcome;

    /** The evidence detail: what the condition observed, bounded to a human sentence. */
    private String detail;

    protected ReleasePolicyEvaluationRuleEntity() {}

    /** One evaluated rule's logged outcome (AC5). */
    public ReleasePolicyEvaluationRuleEntity(
            UUID evaluationId,
            UUID ruleId,
            String ruleName,
            int rulePosition,
            String action,
            String outcome,
            String detail) {
        this.evaluationId = evaluationId;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.rulePosition = rulePosition;
        this.action = action;
        this.outcome = outcome;
        this.detail = detail;
    }

    @PrePersist
    void onCreate() {
        // Append-only: no updatedAt column by design.
    }

    public UUID getId() {
        return id;
    }

    public UUID getEvaluationId() {
        return evaluationId;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public int getRulePosition() {
        return rulePosition;
    }

    public String getAction() {
        return action;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }
}
