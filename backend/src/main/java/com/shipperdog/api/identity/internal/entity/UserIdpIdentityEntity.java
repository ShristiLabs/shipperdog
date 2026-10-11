package com.shipperdog.api.identity.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code user_idp_identities} table (V49, S14-01): the
 * remembered link between a corporate identity (issuer + subject) and
 * a ShipperDog account, scoped to the organization whose provider
 * asserted it. Once linked, logins resolve through the subject and
 * never re-derive the account from email alone.
 */
@Entity
@Table(name = "user_idp_identities")
public class UserIdpIdentityEntity {

    /** How the link was established. */
    public enum LinkKind {
        /** Matched an existing account by verified email. */
        EMAIL,
        /** Created with the account at JIT provisioning. */
        JIT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    private UUID organizationId;

    private String issuerUrl;

    private String subject;

    /** Diagnostic only: the email as asserted at link time. */
    private String emailAtLink;

    @Enumerated(EnumType.STRING)
    private LinkKind linkKind;

    private Instant linkedAt;

    protected UserIdpIdentityEntity() {}

    public UserIdpIdentityEntity(
            UUID userId, UUID organizationId, String issuerUrl, String subject, String emailAtLink, LinkKind linkKind) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.issuerUrl = issuerUrl;
        this.subject = subject;
        this.emailAtLink = emailAtLink;
        this.linkKind = linkKind;
    }

    @PrePersist
    void onCreate() {
        linkedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getIssuerUrl() {
        return issuerUrl;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmailAtLink() {
        return emailAtLink;
    }

    public LinkKind getLinkKind() {
        return linkKind;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
