package com.shipperdog.api.identity.internal.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The SCIM meta attribute (RFC 7643 section 3.1): the resource's type
 * and timestamps. The {@code location} the full form carries is
 * omitted — the SCIM base URL is deployment-relative (per-organization
 * tokens, no per-organization hosts) and the id in the path is the
 * address.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimMeta(String resourceType, Instant created, Instant lastModified) {}
