package com.shipperdog.api.gate;

import java.util.List;
import java.util.UUID;

/**
 * The gate module's definition facts for cross-module readers (issue
 * #43, S10-04): the release module's ring surface renders each ring's
 * gate set (the definitions attached to the ring plus the
 * organization-wide standing) and validates the production ring's
 * gate-set reference (the story's third acceptance criterion) — reads
 * of definition DATA, not evaluation (that is {@link
 * ReleaseGateEvaluation}). Implementation lives in the gate module's
 * {@code .internal.} packages; cross-module callers must use only this
 * interface (enforced by ArchUnit).
 */
public interface GateDefinitionAccess {

    /**
     * The organization's whole definition catalog — the required-gate
     * rows with their ring attachments (null = organization-wide), the
     * caller composes the per-ring sets.
     */
    List<DefinitionFact> definitionsOf(UUID organizationId);

    /** One definition's read facts: the gate type, the required flag, and the ring attachment. */
    record DefinitionFact(String gateType, boolean required, UUID ringId) {}
}
