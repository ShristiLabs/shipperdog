package com.shipperdog.api.audit;

/**
 * Canonical audit event vocabulary. Values are never removed once used;
 * new kinds are appended (audit records are immutable history, PRD 27).
 */
public enum AuditEventType {
    USER_REGISTERED,
    INVITATION_CREATED,
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    USER_LOCKED_OUT,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    TOKEN_ISSUED,
    TOKEN_REFRESHED,
    TOKEN_REVOKED,
    TOKEN_REUSE_DETECTED,
    PAT_CREATED,
    PAT_REVOKED
}
