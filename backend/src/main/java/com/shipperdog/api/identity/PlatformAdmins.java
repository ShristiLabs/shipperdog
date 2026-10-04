package com.shipperdog.api.identity;

import java.util.UUID;

/**
 * The identity module's public platform-administrator check (issue #4).
 * Platform administrators operate above organization scope: they create
 * organizations, administer any of them, and are exempt from the
 * org-deactivation login block. Implementation lives in the identity
 * module's {@code .internal.} packages.
 */
public interface PlatformAdmins {

    /** Whether the user is a platform administrator. */
    boolean isPlatformAdmin(UUID userId);
}
