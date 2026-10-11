package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.config.SsoProperties;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.OrganizationIdpConfigEntity;
import com.shipperdog.api.identity.internal.entity.SsoSessionCodeEntity;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.entity.UserIdpIdentityEntity;
import com.shipperdog.api.identity.internal.repository.SsoSessionCodeRepository;
import com.shipperdog.api.identity.internal.repository.UserIdpIdentityRepository;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.organization.OrganizationException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The provider-neutral "external identity -> user/session" layer
 * (S14-01 born, S14-02 extracted): everything a verified corporate
 * assertion and a ShipperDog account have to do with each other,
 * identical for every provider kind. The OIDC callback and the SAML
 * ACS land here with a validated {@link ExternalIdentity}; neither
 * flow re-implements any of this (the S14-02 story's thin-second-
 * frontend rule, and the shared-tests acceptance criterion).
 *
 * <p>Provisioning policy: INVITED_ONLY links only subjects whose
 * verified email matches an existing member; JIT additionally
 * provisions a password-less account (least-privilege default role)
 * and admits existing non-member accounts with that same role.
 * Platform administrators never authenticate through SSO -- local
 * login is their break-glass (the story's never-lock-operators-out
 * rule). Group/attribute role mapping applies on every login: the
 * most privileged mapped value wins, absence never demotes, and a
 * refused change (the last-ORG_ADMIN guard) never blocks a login.
 *
 * <p>Every refusal publishes {@code SSO_LOGIN_FAILURE} with the
 * distinct reason (the ONLY place it exists -- the browser sees a
 * uniform failure) and unwinds as {@link SsoRefusalException} for the
 * calling flow to render.
 */
@Service
public class SsoAccountResolver {

    /**
     * Role privilege rank for mapping resolution: when several mapped
     * groups match, the most privileged wins (an explicit escalation
     * beats an implicit floor). ORG_ADMIN tops; the tail order among
     * the specialist roles is arbitrary but fixed.
     */
    static final List<OrgRole> ROLE_RANK = List.of(
            OrgRole.VIEWER,
            OrgRole.ORG_MEMBER,
            OrgRole.DEVELOPER,
            OrgRole.QA_ENGINEER,
            OrgRole.APP_OWNER,
            OrgRole.RELEASE_MANAGER,
            OrgRole.SECURITY_REVIEWER,
            OrgRole.COMPLIANCE_REVIEWER,
            OrgRole.ORG_ADMIN);

    /**
     * A provider-verified external identity, already validated by the
     * calling flow's own cryptographic machinery: the subject, its
     * attested email and verification flag, a display name, the
     * enterprise group/role values, and the IdP's inactive assertion
     * (an OIDC claim; SAML has no standard equivalent and passes
     * false).
     */
    public record ExternalIdentity(
            String subject, String email, Boolean emailVerified, String name, List<String> groups, boolean inactive) {}

    private final SsoConfigService configService;
    private final UserIdpIdentityRepository identities;
    private final UserRepository users;
    private final OrganizationAccess organizations;
    private final SsoSessionCodeRepository sessionCodes;
    private final TokenService tokens;
    private final AuditEventPublisher audit;
    private final SsoProperties properties;
    private final Clock clock;

    SsoAccountResolver(
            SsoConfigService configService,
            UserIdpIdentityRepository identities,
            UserRepository users,
            OrganizationAccess organizations,
            SsoSessionCodeRepository sessionCodes,
            TokenService tokens,
            AuditEventPublisher audit,
            SsoProperties properties,
            Clock clock) {
        this.configService = configService;
        this.identities = identities;
        this.users = users;
        this.organizations = organizations;
        this.sessionCodes = sessionCodes;
        this.tokens = tokens;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Resolves the ShipperDog account for a verified corporate
     * assertion, applying the organization's provisioning policy,
     * group-attribute role mapping, and the IdP-inactive lockout.
     *
     * <p>{@code noRollbackFor}: the refusal is a CONTROL-FLOW outcome,
     * not a fault -- the caller catches it and renders the uniform
     * redirect, and the writes made before it (the inactivity lockout
     * above all) must commit. Without the exemption the exception's
     * passage through this transactional boundary would mark the
     * caller's shared transaction rollback-only and turn every
     * uniform refusal into a 500 at commit.
     *
     * @throws SsoRefusalException after the failure audit line
     */
    @Transactional(noRollbackFor = SsoRefusalException.class)
    public UserEntity resolve(
            UUID organizationId, OrganizationIdpConfigEntity config, ExternalIdentity identity, String issuer) {
        String deactivated = organizations
                .statusOf(organizationId)
                .filter("DEACTIVATED"::equals)
                .orElse(null);
        if (deactivated != null) {
            throw refused(null, organizationId, "organization_deactivated");
        }
        if (identity.inactive()) {
            lockForInactivity(organizationId, config, identity, issuer);
            throw refused(null, organizationId, "idp_inactive");
        }
        Optional<UserIdpIdentityEntity> existingLink =
                identities.findByOrganizationIdAndSubject(organizationId, identity.subject());
        if (existingLink.isPresent()) {
            UserEntity linked = users.findById(existingLink.get().getUserId()).orElse(null);
            if (linked == null) {
                throw refused(null, organizationId, "linked_user_gone");
            }
            requireLoginAllowed(linked, organizationId);
            applyRoleMapping(organizationId, config, linked, identity);
            return linked;
        }
        if (identity.email() == null || identity.email().isBlank()) {
            throw refused(null, organizationId, "no_email");
        }
        if (config.isRequireVerifiedEmail() && !Boolean.TRUE.equals(identity.emailVerified())) {
            throw refused(null, organizationId, "unverified_email");
        }
        String email = AuthService.normalizeEmail(identity.email());
        Optional<UserEntity> byEmail = users.findByEmail(email);
        if (byEmail.isPresent()) {
            UserEntity user = byEmail.get();
            if (user.isPlatformAdmin()) {
                // Break-glass: platform administrators authenticate
                // locally, never through an organization's provider.
                throw refused(user.getId(), organizationId, "platform_admin_breakglass");
            }
            requireLoginAllowed(user, organizationId);
            boolean member = organizations.activeOrganizationRoles(user.getId()).containsKey(organizationId);
            if (!member && config.getProvisioningMode() == OrganizationIdpConfigEntity.ProvisioningMode.INVITED_ONLY) {
                throw refused(user.getId(), organizationId, "not_invited");
            }
            if (!member) {
                try {
                    organizations.addMember(
                            organizationId,
                            user.getId(),
                            mappedOrDefault(organizationId, config, identity),
                            user.getId());
                } catch (OrganizationException e) {
                    throw refused(user.getId(), organizationId, "provisioning_refused");
                }
            }
            applyRoleMapping(organizationId, config, user, identity);
            link(organizationId, config, user, identity, UserIdpIdentityEntity.LinkKind.EMAIL, issuer);
            return user;
        }
        if (config.getProvisioningMode() == OrganizationIdpConfigEntity.ProvisioningMode.INVITED_ONLY) {
            throw refused(null, organizationId, "not_invited");
        }
        int atSign = email.indexOf('@');
        UserEntity provisioned = new UserEntity(
                email,
                null,
                identity.name() == null || identity.name().isBlank()
                        ? email.substring(0, atSign > 0 ? atSign : email.length())
                        : identity.name());
        users.save(provisioned);
        try {
            organizations.addMember(
                    organizationId,
                    provisioned.getId(),
                    mappedOrDefault(organizationId, config, identity),
                    provisioned.getId());
        } catch (OrganizationException e) {
            throw refused(provisioned.getId(), organizationId, "provisioning_refused");
        }
        link(organizationId, config, provisioned, identity, UserIdpIdentityEntity.LinkKind.JIT, issuer);
        audit.publish(new AuditEvent(
                AuditEventType.SSO_USER_JIT_PROVISIONED,
                Instant.now(clock),
                provisioned.getId(),
                organizationId,
                null,
                true,
                Map.of(
                        "subject", identity.subject(),
                        "issuer", issuer,
                        "role",
                                mappedOrDefault(organizationId, config, identity)
                                        .name())));
        applyRoleMapping(organizationId, config, provisioned, identity);
        return provisioned;
    }

    /**
     * Mints the one-time session code the flow hands the browser: the
     * SAME mechanism for every provider (the console's exchange stays
     * provider-blind). Stored hashed; TTL per configuration.
     */
    @Transactional
    public String issueSessionCode(UserEntity user, UUID organizationId) {
        String sessionCode = SecureTokens.randomToken();
        sessionCodes.save(new SsoSessionCodeEntity(
                SecureTokens.sha256Hex(sessionCode),
                user.getId(),
                organizationId,
                Instant.now(clock).plus(properties.sessionCodeTtl())));
        return sessionCode;
    }

    /**
     * Publishes the SSO login success line (identical shape for every
     * provider kind; the provisioning mode and issuer say which leg).
     */
    public void auditLoginSuccess(
            UserEntity user, UUID organizationId, OrganizationIdpConfigEntity config, String issuer, String subject) {
        audit.publish(new AuditEvent(
                AuditEventType.SSO_LOGIN_SUCCESS,
                Instant.now(clock),
                user.getId(),
                organizationId,
                null,
                true,
                Map.of(
                        "issuer", issuer,
                        "subject", subject,
                        "provisioningMode", config.getProvisioningMode().name())));
    }

    /** Status gates every resolved account passes (uniform refusal). */
    private void requireLoginAllowed(UserEntity user, UUID organizationId) {
        if (user.getStatus() == UserEntity.Status.DISABLED) {
            throw refused(user.getId(), organizationId, "account_disabled");
        }
        if (user.getStatus() == UserEntity.Status.LOCKED) {
            throw refused(user.getId(), organizationId, "account_locked");
        }
    }

    /**
     * The IdP-inactive lockout (AC: offboarding follows the IdP): the
     * account crosses to LOCKED and every refresh family is revoked
     * (reason {@code idp_inactive}) -- pre-existing sessions die at
     * their next stateful touch, exactly like a tester deactivation.
     * The hard lock refuses refresh, PAT, password login, and SSO
     * alike (the S14-01 review's high finding).
     */
    private void lockForInactivity(
            UUID organizationId, OrganizationIdpConfigEntity config, ExternalIdentity identity, String issuer) {
        identities
                .findByOrganizationIdAndSubject(organizationId, identity.subject())
                .ifPresent(link -> users.findById(link.getUserId()).ifPresent(user -> {
                    user.setStatus(UserEntity.Status.LOCKED);
                    users.save(user);
                    tokens.revokeRefreshTokens(user.getId(), "idp_inactive");
                    audit.publish(new AuditEvent(
                            AuditEventType.SSO_ACCOUNT_LOCKED,
                            Instant.now(clock),
                            user.getId(),
                            organizationId,
                            null,
                            true,
                            Map.of("issuer", issuer, "subject", identity.subject())));
                }));
    }

    /** Records the subject<->user link and its audit line. */
    private void link(
            UUID organizationId,
            OrganizationIdpConfigEntity config,
            UserEntity user,
            ExternalIdentity identity,
            UserIdpIdentityEntity.LinkKind kind,
            String issuer) {
        identities.save(new UserIdpIdentityEntity(
                user.getId(),
                organizationId,
                issuer,
                identity.subject(),
                AuthService.normalizeEmail(identity.email()),
                kind));
        audit.publish(new AuditEvent(
                AuditEventType.SSO_IDENTITY_LINKED,
                Instant.now(clock),
                user.getId(),
                organizationId,
                null,
                true,
                Map.of(
                        "subject", identity.subject(),
                        "linkKind", kind.name(),
                        "issuer", issuer)));
    }

    /**
     * Group/attribute mapping: the most privileged mapped value the
     * token carries wins; no mapped value leaves the membership
     * untouched (absence never demotes). A refused apply (the
     * last-ORG_ADMIN guard) skips the change and records the refusal
     * -- a mapping never blocks a login.
     */
    private void applyRoleMapping(
            UUID organizationId, OrganizationIdpConfigEntity config, UserEntity user, ExternalIdentity identity) {
        if (config.getGroupClaimName() == null || identity.groups().isEmpty()) {
            return;
        }
        Map<String, OrgRole> mappings = configService.mappingsOf(config);
        OrgRole mapped = mappedRole(mappings, identity.groups());
        if (mapped == null) {
            return;
        }
        OrgRole current = organizations.activeOrganizationRoles(user.getId()).get(organizationId);
        if (current == mapped) {
            return;
        }
        try {
            organizations.updateMemberRole(organizationId, user.getId(), mapped, user.getId());
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_ROLE_MAPPED,
                    Instant.now(clock),
                    user.getId(),
                    organizationId,
                    null,
                    true,
                    Map.of(
                            "group", firstMatchedGroup(mappings, identity.groups()),
                            "role.before", current == null ? "" : current.name(),
                            "role.after", mapped.name())));
        } catch (OrganizationException e) {
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_ROLE_MAPPED,
                    Instant.now(clock),
                    user.getId(),
                    organizationId,
                    null,
                    false,
                    Map.of("group", firstMatchedGroup(mappings, identity.groups()), "reason", "role_change_refused")));
        }
    }

    /** The JIT admission role: the mapped role when present, else the configured default. */
    private OrgRole mappedOrDefault(
            UUID organizationId, OrganizationIdpConfigEntity config, ExternalIdentity identity) {
        Map<String, OrgRole> mappings = configService.mappingsOf(config);
        OrgRole mapped = mappedRole(mappings, identity.groups());
        if (mapped != null) {
            return mapped;
        }
        return config.getJitDefaultRole() == null ? OrgRole.VIEWER : config.getJitDefaultRole();
    }

    static OrgRole mappedRole(Map<String, OrgRole> mappings, List<String> groups) {
        OrgRole mapped = null;
        for (String group : groups) {
            OrgRole candidate = mappings.get(group);
            if (candidate != null && (mapped == null || rank(candidate) > rank(mapped))) {
                mapped = candidate;
            }
        }
        return mapped;
    }

    private static String firstMatchedGroup(Map<String, OrgRole> mappings, List<String> groups) {
        List<String> matched = new ArrayList<>(groups);
        matched.retainAll(mappings.keySet());
        return matched.isEmpty() ? "" : matched.getFirst();
    }

    static int rank(OrgRole role) {
        int index = ROLE_RANK.indexOf(role);
        return index < 0 ? 0 : index;
    }

    /**
     * The shared refusal: audits SSO_LOGIN_FAILURE with the distinct
     * reason (the only place it exists) and unwinds as the carrier
     * exception each flow renders uniformly.
     */
    private SsoRefusalException refused(UUID userId, UUID organizationId, String reason) {
        audit.publish(new AuditEvent(
                AuditEventType.SSO_LOGIN_FAILURE,
                Instant.now(clock),
                userId,
                organizationId,
                null,
                false,
                Map.of("reason", reason)));
        return new SsoRefusalException(userId, organizationId, reason);
    }
}
