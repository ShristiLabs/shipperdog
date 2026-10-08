/**
 * Quality and approval gates that releases must pass. First real work:
 * issue #42 (S10-03, FR-026) — the required-gate definitions
 * organizations attach to their rings, the evidence-backed results
 * external systems submit per release, the audited override of a
 * blocking gate, and the evaluation the VALIDATING to READY crossing
 * and S10-05's promotion consult. FR-026's "Approvals" gate is the
 * approval workflow engine (the workflow module) — deliberately not
 * duplicated here.
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.gate;
