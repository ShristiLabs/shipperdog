/**
 * The OTA bundle authoring bounded context (PRD 22; S16-03, issue
 * #165, ADR-0010 workstream 2): bundles are authored create (the
 * dedicated versions row plus the artifact store's upload session) ->
 * bytes direct to storage -> finalize+sign (the manifest, the app's
 * Ed25519 key from the secret-store port, the SIGNED row, the
 * OTA_BUNDLE_SIGNED bus event). The public surface is the controller,
 * the domain exception with its handler, and the signed event; every
 * entity, repository, and service lives under {@code internal.} and
 * must not be imported from other modules (ArchUnit).
 */
package com.shipperdog.api.otabundle;
