package com.shipperdog.api.environment;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The environment module's public read port (issue #58, S9-04): the
 * tuples cross-module callers need — the authorization evaluator's
 * environment-to-organization hop (the {@code 'environment'} target
 * type), the release surface's creation validation (AC4: the tenant
 * assignment set and the lifecycle state), and the S9-03 manifest
 * freeze's deep copy (the environment's code and the RESOLVED
 * configuration values, never pointers). Implementation lives in the
 * module's {@code .internal.} packages; other modules must use only
 * this interface (enforced by ArchUnit).
 */
public interface EnvironmentAccess {

    /**
     * The owning organization of the environment, or empty when the id
     * is unknown — the evaluator's concealment hop and the release
     * surface's organization check.
     */
    Optional<UUID> owningOrganizationOf(UUID environmentId);

    /**
     * The environment's state — id, owner, code, name, position, and
     * lifecycle status — or empty when unknown. The release surface
     * refuses an environment of another organization as the concealed
     * miss (indistinguishable from unknown) and a DISABLED one with the
     * precise conflict.
     */
    Optional<EnvironmentState> stateOf(UUID environmentId);

    /**
     * The environment's CURRENT configuration snapshot: the version and
     * the full resolved key-value map. A never-configured environment
     * answers version 0 with the empty map — the honest empty, never an
     * error (the manifest freezes exactly this answer).
     */
    EnvironmentConfigSnapshot configSnapshotOf(UUID environmentId);

    /**
     * The tenants assigned to the environment (AC4): the set a release
     * creation naming this environment may target. Empty means no
     * tenant may be targeted (deny-by-default, PRD P3).
     */
    Set<UUID> assignedTenantIdsOf(UUID environmentId);

    /**
     * The environment's approval-policy binding (S10-01, issue #40):
     * the NAME-ONLY reference V27 reserved — the name of the approval
     * workflow this environment's releases resolve at the
     * APPROVAL_PENDING crossing, resolved within the owning
     * organization's workflow vocabulary. Empty when the environment
     * binds none; the name's MEANING belongs to the workflow module
     * (an unresolvable name is that module's resolution decision, not
     * this port's).
     */
    Optional<String> approvalPolicyNameOf(UUID environmentId);

    /** The environment's identity and lifecycle as the release surface reads it. */
    record EnvironmentState(UUID id, UUID organizationId, String code, String name, int position, String status) {}

    /** The resolved configuration snapshot as the manifest freeze copies it (deep values, never pointers). */
    record EnvironmentConfigSnapshot(long version, Map<String, String> entries) {}
}
