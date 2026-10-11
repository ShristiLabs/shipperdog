package com.shipperdog.api.identity.internal.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The SCIM Group resource wire shape (S14-03, RFC 7644 section 3.1 /
 * RFC 7643): displayName is the mapping-rules key; members reference
 * SCIM User ids (never internal account identifiers).
 *
 * @param schemas    the schema URNs; tolerated, not validated
 * @param id         the SCIM id; read-only on writes
 * @param externalId the IdP's stable group id, when it sends one
 * @param displayName the group's name — UNIQUE per organization here
 * @param members    the membership list; the {@code value} of each
 *                   entry is the member's SCIM User id
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimGroupResource(
        List<String> schemas, String id, String externalId, String displayName, List<Member> members, ScimMeta meta) {

    /** One group member reference. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(String value, String display, String ref) {}
}
