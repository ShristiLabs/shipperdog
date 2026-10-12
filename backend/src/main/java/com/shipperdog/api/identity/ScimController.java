package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.scim.ScimDiscovery;
import com.shipperdog.api.identity.internal.scim.ScimFilter;
import com.shipperdog.api.identity.internal.scim.ScimGroupResource;
import com.shipperdog.api.identity.internal.scim.ScimListResponse;
import com.shipperdog.api.identity.internal.scim.ScimPatchRequest;
import com.shipperdog.api.identity.internal.scim.ScimUserResource;
import com.shipperdog.api.identity.internal.service.ScimGroupService;
import com.shipperdog.api.identity.internal.service.ScimTokenAuthenticator;
import com.shipperdog.api.identity.internal.service.ScimUserService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SCIM 2.0 provisioning surface (S14-03, issue #69, PRD 25, RFC
 * 7644): {@code /scim/v2/Users} and {@code /scim/v2/Groups} with
 * their discovery documents. Deliberately OUTSIDE the {@code
 * /api/v1} bearer surface — the routes are anonymous at the security
 * chain and carry no method security, because each request's
 * per-organization connector token IS its authentication (the
 * webhook-receiver and download-proxy doctrine: the token's hash
 * lookup is the entire authority, and every authenticity failure
 * answers the SAME 401 SCIM error). The discovery documents answer
 * without a credential: they are deployment-level capability
 * metadata (RFC 7643) carrying no organization or user state.
 *
 * <p>The SCIM protocol keeps its own shapes on purpose: responses
 * are {@code application/scim+json}, pagination is the RFC's
 * one-based startIndex/count (not the platform cursor), and errors
 * are the RFC 7644 section 3.12 document rendered by {@code
 * ScimExceptionHandler} — a documented, deliberate deviation from
 * the platform's problem+json envelope, because SCIM clients parse
 * SCIM errors and nothing else. The infrastructure around the
 * routes (the rate limiter's 429) keeps the platform envelope: that
 * tier is deployment machinery, not SCIM protocol.
 */
@RestController
@RequestMapping("/scim/v2")
public class ScimController {

    /** The SCIM media type (RFC 7644 section 3.1). */
    private static final String SCIM_JSON = "application/scim+json";

    private final ScimTokenAuthenticator authenticator;
    private final ScimUserService users;
    private final ScimGroupService groups;

    ScimController(ScimTokenAuthenticator authenticator, ScimUserService users, ScimGroupService groups) {
        this.authenticator = authenticator;
        this.users = users;
        this.groups = groups;
    }

    // ---------------------------------------------------------------------
    // Discovery (RFC 7643): anonymous capability metadata
    // ---------------------------------------------------------------------

    /** The server's capabilities (IdPs probe this first). */
    @GetMapping(path = "/ServiceProviderConfig", produces = SCIM_JSON)
    @Operation(summary = "SCIM ServiceProviderConfig (capability metadata; anonymous)")
    ScimDiscovery.ServiceProviderConfig serviceProviderConfig() {
        return ScimDiscovery.serviceProviderConfig();
    }

    /**
     * The resource types served. The bare-array answer is the RFC 7644
     * discovery shape (a ListResponse envelope belongs to resource
     * collections, not discovery documents); the ResponseEntity keeps
     * the platform's no-bare-List controller convention.
     */
    @GetMapping(path = "/ResourceTypes", produces = SCIM_JSON)
    @Operation(summary = "SCIM ResourceTypes collection (anonymous capability metadata)")
    ResponseEntity<List<ScimDiscovery.ResourceType>> resourceTypes() {
        return ResponseEntity.ok(ScimDiscovery.resourceTypes());
    }

    /** The attribute contracts served (the same bare-array discovery shape). */
    @GetMapping(path = "/Schemas", produces = SCIM_JSON)
    @Operation(summary = "SCIM Schemas collection (anonymous capability metadata)")
    ResponseEntity<List<ScimDiscovery.SchemaDocument>> schemas() {
        return ResponseEntity.ok(ScimDiscovery.schemas());
    }

    // ---------------------------------------------------------------------
    // Users (RFC 7644 section 3)
    // ---------------------------------------------------------------------

    /** The filtered, paged user listing. */
    @GetMapping(path = "/Users", produces = SCIM_JSON)
    @Operation(summary = "List SCIM users (userName/externalId eq filter; startIndex/count paging)")
    ScimListResponse<ScimUserResource> listUsers(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(name = "count", defaultValue = "100") int count) {
        UUID organizationId = authenticator.authenticate(authorization);
        return users.list(organizationId, ScimFilter.parse(filter), startIndex, count);
    }

    /** Create (the externalId-first upsert: 201 fresh, 200 replay). */
    @PostMapping(path = "/Users", produces = SCIM_JSON)
    @Operation(summary = "Provision a SCIM user (idempotent on externalId; email match links)")
    ResponseEntity<ScimUserResource> createUser(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ScimUserResource resource) {
        UUID organizationId = authenticator.authenticate(authorization);
        ScimUserService.Created created = users.create(organizationId, resource);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(created.resource());
    }

    /** One provisioned user. */
    @GetMapping(path = "/Users/{id}", produces = SCIM_JSON)
    @Operation(summary = "Read one SCIM user by its SCIM id")
    ScimUserResource getUser(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id) {
        return users.get(authenticator.authenticate(authorization), id);
    }

    /** Replace: the full declarative state. */
    @PutMapping(path = "/Users/{id}", produces = SCIM_JSON)
    @Operation(summary = "Replace a SCIM user (active=false deprovisions)")
    ScimUserResource replaceUser(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id,
            @RequestBody ScimUserResource resource) {
        return users.replace(authenticator.authenticate(authorization), id, resource);
    }

    /** Patch: the operations IdPs send (Entra's string booleans included). */
    @PatchMapping(path = "/Users/{id}", produces = SCIM_JSON)
    @Operation(summary = "Patch a SCIM user (attribute replaces; active=false deprovisions)")
    ScimUserResource patchUser(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id,
            @RequestBody ScimPatchRequest request) {
        return users.patch(authenticator.authenticate(authorization), id, request);
    }

    /** Delete: this server's deactivation semantics (the hard-lock cascade). */
    @DeleteMapping(path = "/Users/{id}", produces = SCIM_JSON)
    @Operation(summary = "Deprovision a SCIM user (deactivation cascade, not a row delete)")
    ResponseEntity<Void> deleteUser(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id) {
        users.delete(authenticator.authenticate(authorization), id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------
    // Groups (RFC 7644 section 3.5.1 / 7642): the mapping-rules keys
    // ---------------------------------------------------------------------

    /** The filtered, paged group listing. */
    @GetMapping(path = "/Groups", produces = SCIM_JSON)
    @Operation(summary = "List SCIM groups (displayName/externalId eq filter; startIndex/count paging)")
    ScimListResponse<ScimGroupResource> listGroups(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(name = "count", defaultValue = "100") int count) {
        UUID organizationId = authenticator.authenticate(authorization);
        return groups.list(organizationId, ScimFilter.parse(filter), startIndex, count);
    }

    /** Create a group (the externalId upsert; displayName unique per organization). */
    @PostMapping(path = "/Groups", produces = SCIM_JSON)
    @Operation(summary = "Create a SCIM group (membership changes re-derive assignments)")
    ResponseEntity<ScimGroupResource> createGroup(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ScimGroupResource resource) {
        UUID organizationId = authenticator.authenticate(authorization);
        ScimGroupService.Created created = groups.create(organizationId, resource);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(created.resource());
    }

    /** One pushed group. */
    @GetMapping(path = "/Groups/{id}", produces = SCIM_JSON)
    @Operation(summary = "Read one SCIM group by its SCIM id")
    ScimGroupResource getGroup(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id) {
        return groups.get(authenticator.authenticate(authorization), id);
    }

    /** Replace: the full declarative membership state. */
    @PutMapping(path = "/Groups/{id}", produces = SCIM_JSON)
    @Operation(summary = "Replace a SCIM group's state and membership")
    ScimGroupResource replaceGroup(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id,
            @RequestBody ScimGroupResource resource) {
        return groups.replace(authenticator.authenticate(authorization), id, resource);
    }

    /** Patch: member add/remove (the filter-path and value-array shapes included). */
    @PatchMapping(path = "/Groups/{id}", produces = SCIM_JSON)
    @Operation(summary = "Patch a SCIM group's membership (re-derives affected users)")
    ScimGroupResource patchGroup(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id,
            @RequestBody ScimPatchRequest request) {
        return groups.patch(authenticator.authenticate(authorization), id, request);
    }

    /** Delete: the group and its memberships go; assignments re-derive. */
    @DeleteMapping(path = "/Groups/{id}", produces = SCIM_JSON)
    @Operation(summary = "Delete a SCIM group (memberships go; assignments re-derive)")
    ResponseEntity<Void> deleteGroup(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id) {
        groups.delete(authenticator.authenticate(authorization), id);
        return ResponseEntity.noContent().build();
    }
}
