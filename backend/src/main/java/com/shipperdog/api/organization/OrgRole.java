package com.shipperdog.api.organization;

/**
 * Organization membership roles (issue #4). ORG_ADMIN administers the
 * organization (settings, status, invitations, IdP record); ORG_MEMBER is
 * a regular member. The authorization engine (S1-05) extends this
 * vocabulary when richer role sets are needed.
 */
public enum OrgRole {
    ORG_ADMIN,
    ORG_MEMBER
}
