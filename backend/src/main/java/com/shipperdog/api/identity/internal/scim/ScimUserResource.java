package com.shipperdog.api.identity.internal.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The SCIM User resource wire shape (S14-03, RFC 7644 section 3.1 /
 * RFC 7643 core schema): the fields provisioning clients actually
 * send and read. Unknown sibling attributes — the enterprise
 * extension blocks Entra and Okta append, userType, titles, phone
 * numbers — are ignored on the way in (this server's contract is the
 * core subset) and never re-echoed on the way out.
 *
 * @param schemas    the schema URNs; tolerated, not validated
 * @param id         the SCIM id — a SEPARATE identifier from the
 *                   internal account UUID (the story's technical
 *                   note); read-only on writes
 * @param externalId the IdP's stable person id — the join key
 * @param userName   the IdP's login name (UPN, email, or handle)
 * @param name       the display name components
 * @param active     the provisioning switch: false deprovisions
 * @param emails     the person's addresses; the primary one is the
 *                   account's email
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimUserResource(
        List<String> schemas,
        String id,
        String externalId,
        String userName,
        Name name,
        Boolean active,
        List<Email> emails,
        List<ScimUserResource.GroupRef> groups,
        ScimMeta meta) {

    /** The core name sub-attribute. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Name(String formatted, String familyName, String givenName) {}

    /** One email address; {@code primary} picks the account's address. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Email(String value, String type, Boolean primary) {}

    /** One read-only group reference (RFC 7643: User.groups is derived). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupRef(String value, String display, String ref) {}
}
