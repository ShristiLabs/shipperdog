package com.shipperdog.api.tester;

import java.time.Instant;
import java.util.UUID;

/**
 * One tester invited (or re-invited) into an organization, published
 * in-process (issue #51, S12-01, PRD 51's TESTER_INVITED, FR-023):
 * the invitation hook every later consumer of the onboarding stream
 * subscribes to. The invitation email itself already rides the {@code
 * EmailSender} seam (S6-01); this event is the DOMAIN fact -- the
 * outbox recorder makes it a durable bus event in the same
 * transaction, and subscription-driven channels (in-app digests,
 * webhooks, the S12-02 formatters) route on it from there.
 *
 * <p>The {@code ReleaseTransitionedEvent} discipline: identifiers
 * only -- the tester's uuid and organization, never the email or name
 * (recipients resolve from subscriptions, and PII a listener lacks
 * the authority for never rides an event).
 *
 * @param testerId      the invited tester
 * @param organizationId the organization invited into
 * @param invitedBy     the inviting user
 * @param reinvite      whether this is a re-invitation of an existing INVITED tester
 * @param occurredAt    the invitation moment
 */
public record TesterInvitedEvent(
        UUID testerId, UUID organizationId, UUID invitedBy, boolean reinvite, Instant occurredAt) {}
