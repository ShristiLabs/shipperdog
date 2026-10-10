package com.shipperdog.api.artifact;

import java.util.List;
import java.util.UUID;

/**
 * The artifact module's public surface for verified-source provenance
 * enrichment (issue #53, S12-03, FR-022): the inbound webhook
 * receiver's workflow_run handling backfills CI-run facts onto the
 * artifacts a completed CI run built. Cross-module callers must use
 * only this interface (enforced by ArchUnit); implementation lives in
 * the module's {@code .internal.} packages behind the sanctioned
 * cross-tenant guard (a webhook delivery is not request-scoped -- the
 * commit match spans every organization by design).
 *
 * <p><strong>Backfill-only, never overwrite</strong> -- the deliberate,
 * documented exception to S5-04's write-once provenance contract. The
 * uploader's claims stay the accountability anchor; a signature-
 * verified CI event may only FILL absent fields ({@code ci_run_url},
 * {@code pipeline} -- both already attested by the same CI system
 * that would have sent them at upload time). An asserted value is
 * never replaced, so an attacker who controls either leg can never
 * rewrite history, and every enrichment is audited by the CALLING
 * module with the delivery it rode in on.
 */
public interface ArtifactProvenanceLink {

    /**
     * Backfills {@code ciRunUrl} and {@code pipeline} (each when the
     * caller supplies it non-null AND the artifact's provenance
     * document lacks it) onto every artifact whose frozen S5-04
     * {@code git_commit} claim equals {@code gitCommit}, answering
     * the ids actually enriched. Idempotent by construction: a
     * replayed delivery finds every target field already present and
     * answers an empty list. The caller owns validation of the
     * values' shapes and the audit of every enrichment.
     */
    List<UUID> backfillProvenanceByCommit(String gitCommit, String ciRunUrl, String pipeline);
}
