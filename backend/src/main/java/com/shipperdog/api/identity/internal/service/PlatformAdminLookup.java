package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity module's implementation of the public
 * {@link PlatformAdmins} check (issue #4): the {@code users.platform_admin}
 * flag. Cross-module callers (organization administration) resolve it
 * through the interface only.
 */
@Service
@Transactional(readOnly = true)
public class PlatformAdminLookup implements PlatformAdmins {

    private final UserRepository users;

    PlatformAdminLookup(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean isPlatformAdmin(UUID userId) {
        return users.findById(userId).map(UserEntity::isPlatformAdmin).orElse(false);
    }
}
