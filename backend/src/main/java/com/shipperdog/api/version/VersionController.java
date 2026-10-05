package com.shipperdog.api.version;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.version.internal.service.VersionService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The version API (issue #17, S4-01, FR-008, PRD 8.4): logical versions
 * nested under their application — a release candidate is assembled per
 * version, not per file, and a version can exist without being released
 * (PRD 8.6). Creation validates the semver-style name and rejects
 * duplicates per application (409); the listing orders by the normalized
 * semver key, newest first; the detail carries the per-platform artifact
 * slots (empty until E5 fills them — upload/storage is S5-01) and the
 * release-history section (empty until M2).
 *
 * <p>Routes are application-addressing, so the tenant module's route
 * interceptor binds the owning scope (the owning tenant's, or the owning
 * organization's for a shared application, S3-02) and the repository
 * seam filters every version query to it — versions are visible only
 * through the tenant/authorization chain (issue #17 AC3, S2-02/S3-03).
 * {@code app:versions:read}/{@code app:versions:write} resolve through
 * the application's owning tenant/organization (the #5 catalog bound
 * them to organization roles; this is the first surface that requires
 * them), and resource-scoped denials render as the concealed
 * application miss (PRD 12). Creation honors {@code Idempotency-Key}.
 *
 * <p>The version's tag set (S4-02, FR-009) is replaced wholesale through
 * {@code PUT /{versionId}/tags}. Mutations beyond that — the
 * DRAFT/ACTIVE/ARCHIVED lifecycle, archiving, revocation — are S4-03;
 * version history is permanent (PRD P5), so no endpoint ever deletes a
 * version.
 */
@RestController
@RequestMapping("/api/v1/applications/{applicationId}/versions")
public class VersionController {

    private final VersionService versions;

    VersionController(VersionService versions) {
        this.versions = versions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:versions:write')")
    VersionService.VersionDetail create(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID applicationId,
            @RequestBody CreateRequest request) {
        return versions.create(requireUserId(principal), applicationId, request.versionName());
    }

    @GetMapping
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:versions:read')")
    PageResponse<VersionService.VersionView> list(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID applicationId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<VersionService.VersionView> page = versions.list(applicationId, tag, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    @GetMapping("/{versionId}")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:versions:read')")
    VersionService.VersionDetail get(
            @AuthenticationPrincipal Object principal, @PathVariable UUID applicationId, @PathVariable UUID versionId) {
        return versions.get(applicationId, versionId);
    }

    /**
     * Replaces the version's tag set (issue #18, S4-02, FR-009): the
     * body's whole list becomes the version's tags — trimmed,
     * case-normalized to UPPER, deduplicated; the empty list clears
     * them. Replace-set semantics keep the operation idempotent without
     * an {@code Idempotency-Key} (the registry designates POSTs, per
     * the API conventions), and the reconciliation is audited per real
     * change as {@code VERSION_TAGS_CHANGED}.
     */
    @PutMapping("/{versionId}/tags")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:versions:write')")
    VersionService.TagsView replaceTags(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID applicationId,
            @PathVariable UUID versionId,
            @RequestBody ReplaceTagsRequest request) {
        return versions.replaceTags(requireUserId(principal), applicationId, versionId, request.tags());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new VersionException(VersionException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * Creation body (issue #17 AC1): the semver-style version name, e.g.
     * {@code 7.12.0} — a numeric triple, each segment 0..99999 without
     * leading zeros; pre-release/build suffixes are not part of the
     * vocabulary (tagging is S4-02).
     */
    public record CreateRequest(String versionName) {}

    /**
     * Tag replacement body (issue #18 AC1): the version's entire tag
     * list. Values are trimmed and case-normalized to UPPER on the
     * server ("beta" and "Beta" are BETA); the reserved FR-009
     * vocabulary — QA, UAT, BETA, PILOT, STABLE, PRODUCTION, LEGACY —
     * is the suggested starter set, not a restriction (AC3): arbitrary
     * custom tags of 1..50 characters (letters, digits, underscore,
     * hyphen) are first-class. At most 20 distinct tags per version.
     */
    @Schema(
            description = "The version's entire tag list, replacing any previous set (empty clears it). "
                    + "Values are trimmed and uppercased by the server. Reserved vocabulary (suggested, "
                    + "not enforced): QA, UAT, BETA, PILOT, STABLE, PRODUCTION, LEGACY. Arbitrary custom "
                    + "tags are first-class: 1..50 characters of letters, digits, underscore, or hyphen. "
                    + "At most 20 distinct tags per version.")
    public record ReplaceTagsRequest(List<String> tags) {}
}
