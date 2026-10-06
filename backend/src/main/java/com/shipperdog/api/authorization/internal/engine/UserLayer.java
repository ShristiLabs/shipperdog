package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.identity.UserDirectory;
import org.springframework.stereotype.Component;

/**
 * PRD 11's first chain member: <em>Is the subject known?</em> The
 * engine decides for an authenticated subject — anonymous callers are
 * the security chain's 401, never a decision (issue #30, acceptance
 * criterion 3: authentication failures stay 401s) — so this layer is
 * defense-in-depth: the subject must exist in the user directory. A
 * deleted user with a live token denies here rather than leaking
 * through the resource layers.
 */
@Component
class UserLayer implements AuthorizationLayer {

    private final UserDirectory users;

    UserLayer(UserDirectory users) {
        this.users = users;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String name() {
        return "USER";
    }

    @Override
    public boolean applicable(EvaluationState state, AuthorizationContext context) {
        return true;
    }

    @Override
    public Verdict evaluate(EvaluationState state, AuthorizationContext context) {
        if (!users.userExists(context.userId())) {
            return Verdict.failed(DenialReason.SUBJECT_MISSING, "subject not in the user directory");
        }
        return Verdict.passed("subject known");
    }
}
