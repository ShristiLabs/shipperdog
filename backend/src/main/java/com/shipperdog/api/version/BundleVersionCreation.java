package com.shipperdog.api.version;

import com.shipperdog.api.version.VersionAccess.VersionState;
import java.util.UUID;

/**
 * The version module's creation port for other bounded contexts
 * (S16-03, issue #165, ADR-0010 D2): each OTA bundle owns a dedicated
 * versions row whose label is the bundle version, so every version_id
 * foreign key downstream of releases and analytics stays intact and
 * the REVOKED version lifecycle keeps applying. {@code VersionAccess}
 * is a read surface served by the registry repository; creation is a
 * guarded, audited write, so it rides this single-purpose port
 * implemented by the module's own {@code VersionService} — the
 * archived-application refusal, the unique-label race translation,
 * and the VERSION_CREATED trail entry of the platform's version
 * creation apply unchanged. Callers must have established the owning
 * application's scope (tenant or organization) before calling.
 */
public interface BundleVersionCreation {

    /**
     * Creates the bundle's versions row (label = bundle version),
     * answering the created row's decision tuple.
     */
    VersionState createOtaBundleVersion(UUID actorId, UUID applicationId, String versionName);
}
