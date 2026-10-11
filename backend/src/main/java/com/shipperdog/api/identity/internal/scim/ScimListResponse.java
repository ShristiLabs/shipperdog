package com.shipperdog.api.identity.internal.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The SCIM list response envelope (S14-03, RFC 7644 section 3.4.2.3):
 * startIndex is ONE-based (the SCIM convention — deliberately not the
 * platform's cursor pagination, because SCIM stays SCIM-shaped for
 * its IdP clients), count echoes the page size, and totalResults is
 * the unpaginated total.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimListResponse<T>(
        List<String> schemas, int totalResults, int startIndex, int itemsPerPage, List<T> resources) {

    public static final String SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";

    public ScimListResponse(int totalResults, int startIndex, int itemsPerPage, List<T> resources) {
        this(List.of(SCHEMA), totalResults, startIndex, itemsPerPage, resources);
    }
}
