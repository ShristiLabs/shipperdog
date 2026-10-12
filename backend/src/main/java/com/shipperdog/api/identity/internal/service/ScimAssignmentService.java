package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.identity.internal.entity.ScimGroupEntity;
import com.shipperdog.api.identity.internal.entity.ScimGroupTenantMappingEntity;
import com.shipperdog.api.identity.internal.entity.ScimUserEntity;
import com.shipperdog.api.identity.internal.repository.ScimGroupMemberRepository;
import com.shipperdog.api.identity.internal.repository.ScimGroupRepository;
import com.shipperdog.api.identity.internal.repository.ScimGroupRoleMappingRepository;
import com.shipperdog.api.identity.internal.repository.ScimGroupTenantMappingRepository;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.organization.OrganizationException;
import com.shipperdog.api.tenant.TenantProvisioning;
import com.shipperdog.api.tenant.TenantRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mapped-group assignment derivation (S14-03, issue #69): what a
 * provisioned user's memberships should look like given the groups
 * the IdP currently places them in and the organization's mapping
 * rules. Runs after every user provisioning/update and every group
 * membership change.
 *
 * <p>Organization role: the SSO group-mapping discipline verbatim —
 * the most privileged mapped value the user's groups carry wins, a
 * present mapping is authoritative (up or down), an ABSENT mapping
 * never demotes, and a refused apply (the last-ORG_ADMIN guard) is
 * recorded, never fatal.
 *
 * <p>Tenant memberships: mapped groups GRANT — each (group, tenant)
 * rule of a matching group is ensured for the user (the declarative
 * write {@link TenantProvisioning#ensureMembership} answers). Granting
 * is deliberately one-directional: unmapping a group does not suspend
 * a membership it granted, because a tenant membership row carries no
 * provenance — a manual console grant and a mapping grant are the
 * same row, and a declarative removal would clobber the manual one.
 * Revocation stays with the leaver cascade (deprovision) and the
 * tenant administration routes; the follow-up for provenance-tracked
 * declarative removal is recorded in the story's documentation.
 */
@Service
public class ScimAssignmentService {

    private final ScimGroupMemberRepository groupMembers;
    private final ScimGroupRepository groups;
    private final ScimGroupRoleMappingRepository roleMappings;
    private final ScimGroupTenantMappingRepository tenantMappings;
    private final OrganizationAccess organizations;
    private final TenantProvisioning tenantProvisioning;

    ScimAssignmentService(
            ScimGroupMemberRepository groupMembers,
            ScimGroupRepository groups,
            ScimGroupRoleMappingRepository roleMappings,
            ScimGroupTenantMappingRepository tenantMappings,
            OrganizationAccess organizations,
            TenantProvisioning tenantProvisioning) {
        this.groupMembers = groupMembers;
        this.groups = groups;
        this.roleMappings = roleMappings;
        this.tenantMappings = tenantMappings;
        this.organizations = organizations;
        this.tenantProvisioning = tenantProvisioning;
    }

    /**
     * Re-derives the user's assignments from their current group
     * memberships. The outcome rides the caller's SCIM audit line
     * (the counts and any refused role apply), so the provisioning
     * log answers what each sync did.
     */
    @Transactional
    public Outcome rederive(UUID organizationId, ScimUserEntity scimUser) {
        UUID userId = scimUser.getUserId();
        List<String> groupNames = groupNamesOf(organizationId, scimUser.getId());

        OrgRole mapped = SsoAccountResolver.mappedRole(roleMappingTable(organizationId), groupNames);
        boolean roleRefused = false;
        if (mapped != null) {
            OrgRole current = organizations.activeOrganizationRoles(userId).get(organizationId);
            if (current != mapped) {
                try {
                    organizations.updateMemberRole(organizationId, userId, mapped, userId);
                } catch (OrganizationException e) {
                    // The last-ORG_ADMIN guard: the mapping never blocks
                    // a provisioning, exactly like an SSO login.
                    roleRefused = true;
                }
            }
        }

        int ensured = 0;
        List<String> skips = new ArrayList<>();
        if (!groupNames.isEmpty()) {
            for (Map.Entry<UUID, TenantRole> entry :
                    tenantRulesFor(organizationId, groupNames).entrySet()) {
                TenantProvisioning.Outcome outcome =
                        tenantProvisioning.ensureMembership(entry.getKey(), userId, entry.getValue(), userId);
                switch (outcome) {
                    case CREATED, CHANGED -> ensured++;
                    case SKIPPED_UNKNOWN_TENANT -> skips.add("unknown_tenant");
                    case SKIPPED_INACTIVE_TENANT -> skips.add("inactive_tenant");
                    case UNCHANGED -> {}
                }
            }
        }
        return new Outcome(mapped, roleRefused, ensured, skips.size());
    }

    private List<String> groupNamesOf(UUID organizationId, UUID scimUserId) {
        return groupMembers.findByScimUserId(scimUserId).stream()
                .map(membership -> groups.findById(membership.getScimGroupId()).orElse(null))
                .filter(group -> group != null && organizationId.equals(group.getOrganizationId()))
                .map(ScimGroupEntity::getDisplayName)
                .toList();
    }

    private Map<String, OrgRole> roleMappingTable(UUID organizationId) {
        Map<String, OrgRole> table = new LinkedHashMap<>();
        roleMappings
                .findByOrganizationId(organizationId)
                .forEach(mapping -> table.put(mapping.getGroupName(), mapping.getOrgRole()));
        return table;
    }

    /**
     * The effective tenant rules for the user's groups: several groups
     * may map to the same tenant with different roles — TENANT_ADMIN
     * outranks TENANT_TESTER (the explicit escalation wins).
     */
    private Map<UUID, TenantRole> tenantRulesFor(UUID organizationId, List<String> groupNames) {
        Set<String> names = new LinkedHashSet<>(groupNames);
        Map<UUID, TenantRole> byTenant = new LinkedHashMap<>();
        for (ScimGroupTenantMappingEntity rule : tenantMappings.findByOrganizationId(organizationId)) {
            if (!names.contains(rule.getGroupName())) {
                continue;
            }
            byTenant.merge(rule.getTenantId(), rule.getTenantRole(), ScimAssignmentService::mostPrivileged);
        }
        return byTenant;
    }

    private static TenantRole mostPrivileged(TenantRole first, TenantRole second) {
        return first == TenantRole.TENANT_ADMIN || second == TenantRole.TENANT_ADMIN
                ? TenantRole.TENANT_ADMIN
                : TenantRole.TENANT_TESTER;
    }

    /** What the derivation did, for the caller's audit line. */
    public record Outcome(OrgRole mappedRole, boolean roleRefused, int tenantsEnsured, int tenantsSkipped) {}
}
