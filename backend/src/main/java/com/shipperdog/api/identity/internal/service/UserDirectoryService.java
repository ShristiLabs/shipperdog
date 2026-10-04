package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.identity.UserDirectory;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserDirectory binding (issue #5): display-field lookups for other
 * modules' member listings. The {@code users} table stays inside the
 * identity module's internal packages; nothing beyond these non-sensitive
 * fields crosses the boundary.
 */
@Service
public class UserDirectoryService implements UserDirectory {

    private final UserRepository users;

    UserDirectoryService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummary> byIds(Collection<UUID> userIds) {
        return users.findAllById(userIds).stream()
                .map(user -> new UserSummary(user.getId(), user.getEmail(), user.getFullName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean userExists(UUID userId) {
        return users.existsById(userId);
    }
}
