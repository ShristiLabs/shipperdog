package com.shipperdog.api.notification.internal.repository;

import com.shipperdog.api.notification.internal.entity.NotificationSubscriptionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The subscription table's persistence (V39): org-scoped reads and
 * writes (the rings pattern -- reference data of one organization,
 * never another's).
 */
public interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscriptionEntity, UUID> {

    /** The organization's subscriptions, newest first (the console listing). */
    List<NotificationSubscriptionEntity> findByOrganizationIdOrderByCreatedAtDesc(
            UUID organizationId, Pageable pageable);

    long countByOrganizationId(UUID organizationId);

    /**
     * One subscription inside its organization: a subscription of
     * another organization simply does not answer -- the concealed
     * 404 the service renders.
     */
    Optional<NotificationSubscriptionEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The dispatcher's routing read: the organization's live subscriptions. */
    List<NotificationSubscriptionEntity> findByOrganizationIdAndEnabledTrue(UUID organizationId);
}
