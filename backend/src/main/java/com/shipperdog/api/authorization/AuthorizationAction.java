package com.shipperdog.api.authorization;

/**
 * The actions the layered authorization engine (issue #30, S7-01, PRD 11)
 * decides. The action selects which layers are applicable ("a request
 * should pass through every applicable authorization layer") and which
 * entitlement the version layer requires:
 *
 * <ul>
 *   <li>{@link #LIST_APPLICATIONS} — the tester catalog read: the
 *       subject asking which applications a tenant sees. Evaluates the
 *       user, organization, and tenant layers; the application layer's
 *       set form is the sanctioned effective-visibility union the
 *       catalog listing itself runs (ADR-0002 fourth amendment), so the
 *       per-item layer is skipped for list decisions.</li>
 *   <li>{@link #READ_ARTIFACT} — the artifact fetch: metadata reads of
 *       one artifact under one version. Evaluates every layer the ids
 *       address; the artifact layer checks existence and version
 *       ownership but no lifecycle availability (a VALIDATING artifact
 *       is readable — the read-back surface is where extraction
 *       settles).</li>
 *   <li>{@link #DOWNLOAD} — the download decision of PRD 12's example:
 *   every layer, the strictest availability semantics (version not
 *   REVOKED per INV-007/S4-03, artifact platform match and READY or
 *   DISTRIBUTED per PRD 18), the FR-011 entitlement check, and the
 *   distribution layer. E8's tester portal issues this decision for
 *   every download; S7-03 exposes it as the decision API.</li>
 * </ul>
 */
public enum AuthorizationAction {
    LIST_APPLICATIONS,
    READ_ARTIFACT,
    DOWNLOAD
}
