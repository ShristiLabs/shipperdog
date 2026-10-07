/**
 * Releases and their lifecycle state machine. S9-01 (issue #55) landed
 * the model, creation, and targets: the DRAFT distribution event with
 * per-platform artifacts, target tenants + tester groups, and the
 * starting ring (the S10-04 ring data model pulled forward as reference
 * data); the state machine itself is S9-02, the manifest S9-03.
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.release;
