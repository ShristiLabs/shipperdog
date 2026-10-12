package com.shipperdog.api.authorization.internal.abac;

import java.util.UUID;

/**
 * The ABAC engine's complete answer for one request (issue #70,
 * S14-04): ABSTAIN (no applicable policy — the base decision stands
 * untouched) or DENIED (a policy refused — the only narrowing the
 * engine can produce; there is no ALLOW outcome because ABAC never
 * widens, AC2). The denial carries the winning policy's identity and
 * the decision explanation — which rule fired and the evaluated
 * attributes — the text AC3 routes to the inspect surface and the
 * audit trail.
 *
 * @param denied      whether a policy denied
 * @param ruleId      the winning policy, or null when abstaining
 * @param ruleName    the winning policy's name, or null
 * @param version     the winning policy's version, or 0
 * @param fault       whether the denial is a fail-closed fault answer (AC6) rather than an authored refusal
 * @param explanation the decision explanation (which rule, which attributes, what they resolved to)
 */
public record AbacResult(boolean denied, UUID ruleId, String ruleName, int version, boolean fault, String explanation) {

    /** No applicable policy: the base decision stands. */
    static AbacResult abstain() {
        return new AbacResult(false, null, null, 0, false, "no applicable ABAC policy");
    }

    /** An authored refusal (or a fail-closed fault answer): the winning policy and its explanation. */
    static AbacResult denied(UUID ruleId, String ruleName, int version, boolean fault, String explanation) {
        return new AbacResult(true, ruleId, ruleName, version, fault, explanation);
    }

    /** The rendered refusal (the promotion 409's errors member and the audit detail). */
    public String refusal() {
        return "ABAC policy '" + ruleName + "' (v" + version + ")"
                + (fault ? " failed closed: " : " denied: ")
                + explanation;
    }
}
