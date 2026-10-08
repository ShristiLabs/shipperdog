package com.shipperdog.api.release.internal.policy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the evaluation log's per-rule outcomes (V34). The child
 * rows of one evaluation, in the defined order the engine walked.
 */
public interface ReleasePolicyEvaluationRuleRepository extends JpaRepository<ReleasePolicyEvaluationRuleEntity, UUID> {

    /** One evaluation's per-rule outcomes, in the order they were evaluated (AC5). */
    List<ReleasePolicyEvaluationRuleEntity> findByEvaluationIdOrderByRulePositionAscRuleNameAsc(UUID evaluationId);
}
