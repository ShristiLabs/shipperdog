package com.shipperdog.api.authorization.internal.abac;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The {@code abac_policy_versions} history store (issue #70,
 * S14-04): append-only reads of the immutable version rows — the
 * newest-first listing the admin history surface serves, keyed by the
 * LOOSE policy id (rows survive the policy row's deletion).
 */
public interface AbacPolicyVersionRepository extends JpaRepository<AbacPolicyVersionEntity, UUID> {

    /** The policy's versions, newest first (history survives deletion — the loose-key reads stay valid). */
    List<AbacPolicyVersionEntity> findByPolicyIdOrderByVersionDesc(UUID policyId);
}
