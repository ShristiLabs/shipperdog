package com.shipperdog.api.identity.internal.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The SCIM discovery documents (S14-03, RFC 7643): the static
 * capability metadata IdP provisioning clients probe before their
 * first write — ServiceProviderConfig (what this server supports),
 * ResourceTypes (what it serves), and Schemas (the attribute
 * contract). Deployment-level facts only: no organization, user, or
 * token state appears in any of them, which is why the routes answer
 * without a bearer credential.
 */
public final class ScimDiscovery {

    public static final String SP_CONFIG_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    public static final String RESOURCE_TYPE_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";

    public static final String SCHEMA_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema";

    /** The ServiceProviderConfig document (RFC 7643 section 5). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceProviderConfig(
            List<String> schemas,
            String documentationUri,
            Feature patch,
            Bulk bulk,
            Feature filter,
            Feature changePassword,
            Feature sort,
            Feature etag,
            List<AuthenticationScheme> authenticationSchemes,
            ScimMeta meta) {

        /** One boolean capability flag. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Feature(boolean supported) {}

        /** The bulk capability: unsupported here, stated honestly. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Bulk(boolean supported, int maxOperations, int maxPayloadSize) {}

        /** One authentication scheme the server accepts. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record AuthenticationScheme(
                String type, String name, String description, String specUri, Boolean primary) {}
    }

    /** One resource type descriptor (RFC 7643 section 6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceType(
            List<String> schemas,
            String id,
            String name,
            String description,
            String endpoint,
            String schema,
            List<String> schemaExtensions,
            ScimMeta meta) {}

    /** One schema document (RFC 7643 section 7): the attribute contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SchemaDocument(
            List<String> schemas, String id, String name, String description, List<Attribute> attributes) {

        /** One attribute descriptor. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Attribute(
                String name,
                String type,
                String description,
                Boolean required,
                Boolean caseExact,
                Boolean multiValued,
                String returned,
                List<String> subAttributes) {}
    }

    /** The ServiceProviderConfig this server answers. */
    public static ServiceProviderConfig serviceProviderConfig() {
        return new ServiceProviderConfig(
                List.of(SP_CONFIG_SCHEMA),
                null,
                new ServiceProviderConfig.Feature(true),
                new ServiceProviderConfig.Bulk(false, 0, 0),
                new ServiceProviderConfig.Feature(true),
                new ServiceProviderConfig.Feature(false),
                new ServiceProviderConfig.Feature(false),
                new ServiceProviderConfig.Feature(false),
                List.of(new ServiceProviderConfig.AuthenticationScheme(
                        "oauthbearertoken",
                        "SCIM Connector Bearer Token",
                        "The per-organization connector token generated on the SCIM configuration surface"
                                + " (one live token per organization; rotation replaces it).",
                        "https://www.rfc-editor.org/rfc/rfc7644#section-2.1",
                        true)),
                new ScimMeta("ServiceProviderConfig", null, null));
    }

    /** The resource types this server serves. */
    public static List<ResourceType> resourceTypes() {
        return List.of(
                new ResourceType(
                        List.of(RESOURCE_TYPE_SCHEMA),
                        "User",
                        "User",
                        "The provisioned console user account (joiner/leaver lifecycle)",
                        "/scim/v2/Users",
                        USER_SCHEMA_ID,
                        null,
                        new ScimMeta("ResourceType", null, null)),
                new ResourceType(
                        List.of(RESOURCE_TYPE_SCHEMA),
                        "Group",
                        "Group",
                        "The pushed group whose display name keys the organization's mapping rules",
                        "/scim/v2/Groups",
                        GROUP_SCHEMA_ID,
                        null,
                        new ScimMeta("ResourceType", null, null)));
    }

    /** The schema URNs of the resources (also the Schemas collection's ids). */
    public static final String USER_SCHEMA_ID = "urn:ietf:params:scim:schemas:core:2.0:User";

    public static final String GROUP_SCHEMA_ID = "urn:ietf:params:scim:schemas:core:2.0:Group";

    public static final String PATCH_SCHEMA_ID = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** The Schemas collection: the core User, Group, and PatchOp contracts. */
    public static List<SchemaDocument> schemas() {
        return List.of(
                new SchemaDocument(
                        List.of(SCHEMA_SCHEMA),
                        USER_SCHEMA_ID,
                        "User",
                        "The provisioned user account",
                        List.of(
                                new SchemaDocument.Attribute(
                                        "id",
                                        "string",
                                        "The SCIM id (stable, separate from internal ids)",
                                        null,
                                        null,
                                        null,
                                        "readOnly",
                                        null),
                                new SchemaDocument.Attribute(
                                        "externalId",
                                        "string",
                                        "The IdP's stable person id; the join key of provisioning",
                                        true,
                                        null,
                                        null,
                                        null,
                                        null),
                                new SchemaDocument.Attribute(
                                        "userName", "string", "The IdP's login name", true, null, null, null, null),
                                new SchemaDocument.Attribute(
                                        "name",
                                        "complex",
                                        "The display name components",
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of("formatted", "familyName", "givenName")),
                                new SchemaDocument.Attribute(
                                        "active",
                                        "boolean",
                                        "The provisioning switch: false deprovisions (hard lock)",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null),
                                new SchemaDocument.Attribute(
                                        "emails",
                                        "complex",
                                        "The person's addresses (primary picks the account email)",
                                        null,
                                        null,
                                        true,
                                        null,
                                        List.of("value", "type", "primary")),
                                new SchemaDocument.Attribute(
                                        "groups",
                                        "complex",
                                        "The derived group memberships (read-only)",
                                        null,
                                        null,
                                        true,
                                        "readOnly",
                                        List.of("value", "display", "ref")))),
                new SchemaDocument(
                        List.of(SCHEMA_SCHEMA),
                        GROUP_SCHEMA_ID,
                        "Group",
                        "The pushed group",
                        List.of(
                                new SchemaDocument.Attribute(
                                        "id",
                                        "string",
                                        "The SCIM id (stable, separate from internal ids)",
                                        null,
                                        null,
                                        null,
                                        "readOnly",
                                        null),
                                new SchemaDocument.Attribute(
                                        "externalId",
                                        "string",
                                        "The IdP's stable group id",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null),
                                new SchemaDocument.Attribute(
                                        "displayName",
                                        "string",
                                        "The group's name; the mapping-rules key",
                                        true,
                                        null,
                                        null,
                                        null,
                                        null),
                                new SchemaDocument.Attribute(
                                        "members",
                                        "complex",
                                        "The membership list (value = SCIM User id)",
                                        null,
                                        null,
                                        true,
                                        null,
                                        List.of("value", "display", "ref")))),
                new SchemaDocument(
                        List.of(SCHEMA_SCHEMA),
                        PATCH_SCHEMA_ID,
                        "PatchOp",
                        "The PATCH operation structure",
                        List.of(
                                new SchemaDocument.Attribute(
                                        "op", "string", "add, replace, or remove", true, null, null, null, null),
                                new SchemaDocument.Attribute(
                                        "path", "string", "The target attribute path", null, null, null, null, null),
                                new SchemaDocument.Attribute(
                                        "value",
                                        null,
                                        "The value the operation applies",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))));
    }

    private ScimDiscovery() {}
}
