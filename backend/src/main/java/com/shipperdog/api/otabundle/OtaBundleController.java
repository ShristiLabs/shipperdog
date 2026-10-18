package com.shipperdog.api.otabundle;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.otabundle.internal.service.OtaBundleService;
import com.shipperdog.api.otabundle.internal.service.OtaBundleService.BundleView;
import com.shipperdog.api.otabundle.internal.service.OtaBundleService.DraftView;
import com.shipperdog.api.otabundle.internal.service.OtaBundleService.FinalizeView;
import com.shipperdog.api.otabundle.internal.service.OtaBundleService.SigningKeyView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OTA bundle authoring API (S16-03, issue #165, ADR-0010
 * workstream 2): {@code POST /api/v1/ota/bundles} creates the draft —
 * the dedicated versions row (D2) plus the artifact module's
 * direct-to-storage session grant (D5) — the client streams the
 * bundle's bytes to the granted URL, and {@code POST
 * /api/v1/ota/bundles/{id}/finalize} lands the manifest, signs under
 * the app's Ed25519 key (D8), and answers the SIGNED bundle. The
 * finalize is replay-safe: the bundle's id is the pre-allocated
 * artifact id the create answered, so a retry replays the landed row.
 *
 * <p>Routes are application-addressed the way the artifact routes
 * are: the create's body and the reads' {@code applicationId} (or the
 * finalize's {@code versionId}) query parameter carry the permission
 * target, and the permission is the artifact family's — {@code
 * app:artifacts:upload} for authoring, {@code app:artifacts:read} for
 * reads (a bundle IS an artifact ride-along, D5; the DEVELOPER and
 * APP_OWNER personas it fits). Foreign or unknown ids are the
 * concealed miss; the OTA-disabled or archived application is the
 * 409/404 of the module's own registry codes.
 */
@RestController
@RequestMapping("/api/v1/ota")
public class OtaBundleController {

    private final OtaBundleService bundles;

    OtaBundleController(OtaBundleService bundles) {
        this.bundles = bundles;
    }

    /**
     * Creates the bundle draft: validates the authoring surface,
     * creates the bundle's dedicated versions row (label = the bundle
     * version, D2), and grants the direct-to-storage upload session
     * for the bundle's bytes. The answer carries the bundle id (the
     * pre-allocated artifact id — the bytes ARE the bundle), the
     * version id, and the session URL with its expiry; nothing is
     * listable until finalize lands the SIGNED row. Honors
     * {@code Idempotency-Key}.
     */
    @PostMapping("/bundles")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:artifacts:upload')")
    DraftView create(@AuthenticationPrincipal Object principal, @RequestBody CreateBundleRequest request) {
        return bundles.create(
                requireUserId(principal),
                new OtaBundleService.CreateCommand(
                        request.applicationId(),
                        request.bundleVersion(),
                        request.platformTag(),
                        request.fileName(),
                        Boolean.TRUE.equals(request.forced()),
                        request.minAppVersion(),
                        request.sizeHint()));
    }

    /**
     * Finalizes and signs the bundle (the finalize+sign of ADR-0010's
     * authoring flow): the artifact port hashes the stored bytes back
     * and registers the binary, the manifest lands (sha256, sizeBytes,
     * platformTag), the checksum is signed under the app's active
     * Ed25519 key — generated now through the secret-store port if
     * this is the app's first bundle — and the SIGNED bundle row plus
     * its {@code OTA_BUNDLE_SIGNED} bus event commit together. 201 the
     * first landing, 200 on every replay: the state is the answer.
     * Honors {@code Idempotency-Key}.
     */
    @PostMapping("/bundles/{bundleId}/finalize")
    @Idempotent
    @PreAuthorize("hasPermission(#request.versionId(), 'version', 'app:artifacts:upload')")
    ResponseEntity<FinalizeView> finalize(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID bundleId,
            @RequestBody FinalizeRequest request) {
        FinalizeView outcome = bundles.finalizeBundle(requireUserId(principal), bundleId, request.versionId());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome);
    }

    /**
     * Reads one bundle. The {@code applicationId} query parameter is
     * the permission target (the artifact read's body-addressed
     * shape); a foreign or unknown bundle is the concealed miss.
     */
    @GetMapping("/bundles/{bundleId}")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:artifacts:read')")
    BundleView get(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID bundleId,
            @RequestParam("applicationId") UUID applicationId) {
        return bundles.get(requireUserId(principal), bundleId, applicationId);
    }

    /**
     * Lists the application's bundles, newest first, standard
     * {@code limit}/{@code cursor} pagination; the optional
     * {@code status} filter narrows by the V55 vocabulary (UPLOADING,
     * VALIDATING, SIGNED, RELEASED, REVOKED).
     */
    @GetMapping("/bundles")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:artifacts:read')")
    PageResponse<BundleView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam("applicationId") UUID applicationId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        return bundles.list(requireUserId(principal), applicationId, status, query.limit(), query.offset());
    }

    /**
     * Revokes a SIGNED bundle: the author's withdrawal (REVOKED, the
     * V55 vocabulary; the device-side refusal lands with the check
     * resolution, S16-07). Only a SIGNED bundle is revocable through
     * this surface. Honors {@code Idempotency-Key}.
     */
    @PostMapping("/bundles/{bundleId}/revoke")
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:artifacts:upload')")
    BundleView revoke(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID bundleId,
            @RequestBody RevokeRequest request) {
        return bundles.revoke(requireUserId(principal), bundleId, request.applicationId());
    }

    /**
     * The application's ACTIVE OTA signing key for console/SDK
     * embedding (D8: the public half plus its key id, copyable before
     * the SDK ships). Before the app's first finalized bundle there is
     * no key — generation is a finalize-time act, never a read side
     * effect — and this read answers the honest 404.
     */
    @GetMapping("/signing-keys")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:artifacts:read')")
    SigningKeyView signingKey(
            @AuthenticationPrincipal Object principal, @RequestParam("applicationId") UUID applicationId) {
        return bundles.signingKeyOf(requireUserId(principal), applicationId);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The bundle creation body: the owning application, the bundle's
     * version label (a semver triple — it labels the bundle's own
     * versions row, D2), the open-vocabulary platform tag, the bare
     * bundle file name (any extension), the per-bundle defaults of D7,
     * and the optional size hint in bytes.
     */
    @Schema(
            description = "The OTA bundle to author: applicationId, the semver-triple bundleVersion labeling"
                    + " its dedicated versions row, the open-vocabulary platformTag (1-32 chars), the bare"
                    + " fileName of the bundle archive, the per-bundle forced/minAppVersion defaults, and"
                    + " the optional sizeHint in bytes.")
    public record CreateBundleRequest(
            UUID applicationId,
            String bundleVersion,
            String platformTag,
            String fileName,
            Boolean forced,
            String minAppVersion,
            Long sizeHint) {}

    /** The finalize body: the version the bundle's session belongs to. */
    @Schema(description = "The version id the bundle's dedicated versions row carries.")
    public record FinalizeRequest(UUID versionId) {}

    /** The revoke body: the owning application (the permission target). */
    @Schema(description = "The application id the revoked bundle belongs to.")
    public record RevokeRequest(UUID applicationId) {}
}
