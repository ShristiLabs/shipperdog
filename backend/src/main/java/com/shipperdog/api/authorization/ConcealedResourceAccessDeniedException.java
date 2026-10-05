package com.shipperdog.api.authorization;

import org.springframework.security.access.AccessDeniedException;

/**
 * A permission denial on a specific target resource that must be concealed
 * (PRD 12, issue #8): when the caller is not entitled to even learn that
 * the resource exists, the response is a 404-shaped problem that is byte-
 * for-byte what a genuine miss returns. The evaluator throws this for
 * resource-scoped denials ({@code hasPermission(#id, ...)}); permission
 * denials that are not about a specific resource (e.g. creation rights)
 * stay plain opaque 403s.
 */
public final class ConcealedResourceAccessDeniedException extends AccessDeniedException {

    private final String targetType;

    /**
     * @param targetType the guarded resource type ({@code 'organization'},
     *        {@code 'tenant'}, ...) — selects which module's not-found code
     *        the 404 renders with, so concealment and genuine misses are
     *        indistinguishable
     */
    public ConcealedResourceAccessDeniedException(String targetType) {
        super("access denied");
        this.targetType = targetType;
    }

    public String targetType() {
        return targetType;
    }
}
