-- V30__approval_skipped_shape.sql
-- The SKIPPED leg of the approval decision shape (issue #41, S10-02).
--
-- V29 added the SKIPPED approval status; this migration lands the
-- CHECK that references it (a new enum value cannot be used in the
-- transaction that adds it, and Flyway runs each migration as one
-- transaction — hence the pair).
--
-- The three-leg decision shape: PENDING carries nothing decided;
-- SKIPPED carries no signature but must explain itself and when (the
-- skip is a recorded decision ABOUT the step: which signature
-- satisfied the ANY group, or why the condition excluded it); the
-- signed statuses (APPROVED/REJECTED) carry the full signature. The
-- V28 two-leg check is replaced by this widening — every existing
-- row satisfies the new legs unchanged (SKIPPED did not exist), so
-- the drop-and-re-add carries no data.

ALTER TABLE release_approvals DROP CONSTRAINT ck_release_approvals_decision_shape;

ALTER TABLE release_approvals
    ADD CONSTRAINT ck_release_approvals_decision_shape CHECK (
        (status = 'PENDING' AND approver IS NULL AND approver_role IS NULL AND decided_at IS NULL)
        OR (status = 'SKIPPED' AND approver IS NULL AND approver_role IS NULL
            AND decided_at IS NOT NULL AND reason IS NOT NULL)
        OR (status NOT IN ('PENDING', 'SKIPPED') AND approver IS NOT NULL
            AND approver_role IS NOT NULL AND decided_at IS NOT NULL)
    );
