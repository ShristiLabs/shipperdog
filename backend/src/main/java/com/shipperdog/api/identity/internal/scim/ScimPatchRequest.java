package com.shipperdog.api.identity.internal.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The SCIM PATCH request shape (S14-03, RFC 7644 section 3.5.2): a
 * list of operations against attribute paths. The {@code Operations}
 * key is capital in the RFC's JSON; Entra, Okta, and Workspace all
 * spell it that way.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimPatchRequest(
        List<String> schemas, @JsonProperty("Operations") List<Operation> operations) {

    /**
     * One PatchOp. {@code value} stays raw: its shape depends on the
     * path (a scalar for attribute replaces, an object for complex
     * attributes, a member array for group membership, and — the
     * Entra quirk — booleans arrive as the STRINGS "True"/"False").
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Operation(String op, String path, Object value) {}
}
