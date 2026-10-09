package com.shipperdog.api.feedback.internal.service;

import com.shipperdog.api.feedback.FeedbackMarkedCriticalEvent;
import com.shipperdog.api.identity.UserDirectory;
import com.shipperdog.api.notification.EmailSender;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The critical-feedback notification consumer (issue #47, S11-02,
 * AC5): one in-process listener on {@link FeedbackMarkedCriticalEvent}
 * that delivers the platform's default role subscription through the
 * {@code EmailSender} seam — the M2 delivery path until S12-01's
 * notifications epic formalizes channels, templates, and per-user
 * preferences on top of the same event (the bus will relay it then;
 * the event is the contract, this listener the interim consumer).
 *
 * <p>The default subscription is the platform's, not a preference
 * model: the owning organization's QA engineers and administrators
 * hear about every critical report, because those are the roles that
 * work the triage queue the metric feeds. The SECURITY_ISSUE
 * concealment discipline governs the subscription itself: a critical
 * security report notifies the restricted visibility's reader set —
 * the organization's administrators and security reviewers alone —
 * never the general queue (a QA engineer must not learn a security
 * report exists through a notification the read surface refuses
 * them).
 *
 * <p>The message carries identifiers only (the event's own PII
 * minimization posture): the release ref, the report's type, and
 * where to find it. The description's content never rides a
 * notification.
 */
@Component
public class FeedbackCriticalNotifier {

    private final OrganizationAccess organizations;

    private final UserDirectory directory;

    private final EmailSender email;

    FeedbackCriticalNotifier(OrganizationAccess organizations, UserDirectory directory, EmailSender email) {
        this.organizations = organizations;
        this.directory = directory;
        this.email = email;
    }

    @EventListener
    void on(FeedbackMarkedCriticalEvent event) {
        // The subscribed roles, concealment-aware (the security-issue
        // branch names the restricted reader set alone).
        Set<OrgRole> subscribed = "SECURITY_ISSUE".equals(event.type())
                ? Set.of(OrgRole.ORG_ADMIN, OrgRole.SECURITY_REVIEWER)
                : Set.of(OrgRole.ORG_ADMIN, OrgRole.QA_ENGINEER);
        Set<UUID> subscribers = new LinkedHashSet<>();
        for (OrgRole role : subscribed) {
            subscribers.addAll(organizations.memberUserIdsOfRole(event.organizationId(), role));
        }
        if (subscribers.isEmpty()) {
            return;
        }
        String body = "A tester report was classified CRITICAL and is now counted by the "
                + "open-critical-bugs gate metric.\n\n"
                + "Release: "
                + event.releaseRef()
                + "\nReport type: "
                + event.type()
                + "\nReport id: "
                + event.feedbackId()
                + "\nClassified at: "
                + event.occurredAt()
                + "\n\nOpen the release's feedback triage surface to work the report.";
        for (UserDirectory.UserSummary member : directory.byIds(subscribers)) {
            email.send(member.email(), "Critical feedback on " + event.releaseRef(), body);
        }
    }
}
