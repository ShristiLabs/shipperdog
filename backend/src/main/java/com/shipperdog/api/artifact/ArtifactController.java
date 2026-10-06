package com.shipperdog.api.artifact;

import com.shipperdog.api.artifact.internal.service.ArtifactService;
import com.shipperdog.api.artifact.internal.service.ArtifactService.ArtifactView;
import com.shipperdog.api.artifact.internal.service.ArtifactService.FinalizeView;
import com.shipperdog.api.artifact.internal.service.ArtifactService.UploadSessionView;
import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The artifact upload API (issue #20, S5-01, FR-004, PRD 19): one
 * body-addressed collection route — {@code POST /api/v1/artifacts}
 * carries the version id in the multipart form, so the permission
 * check targets the version ({@code app:artifacts:upload} through the
 * evaluator's version-to-application-to-owner hop) and the service
 * establishes the owning scope through the sanctioned executor once
 * the version resolves. Two paths land here:
 *
 * <ul>
 *   <li>The multipart route streams the bytes through the server —
 *       SHA-256 computed during the stream — for artifacts at or below
 *       the resumable threshold; it honors {@code Idempotency-Key}
 *       (the designated-POST registry S5-01 joins, per the API
 *       conventions).</li>
 *   <li>The resumable route (issue #20 AC5, the >100 MB rule): create
 *       a short-lived session, stream bytes direct to storage, then
 *       finalize — the server hashes the stored object, registers the
 *       row, and answers; a finalize retry replays the registered
 *       state (the terminal state is the replay answer, so finalize
 *       takes no {@code Idempotency-Key}).</li>
 * </ul>
 *
 * <p>Form fields keep the issue's snake_case names ({@code version_id},
 * {@code platform}, {@code build_number}); JSON bodies keep the
 * platform's lowerCamelCase convention. Every rejection renders the
 * problem+json envelope: unknown extensions pre-storage (415), the
 * size ceiling (413), a pinned build already taken (409), an archived
 * application (409, the code reserved for this surface since issue
 * #13), a frozen version (409 {@code VERSION_IMMUTABLE} through the
 * version module's handler), and the concealed 404s of unknown or
 * foreign versions and artifacts.
 */
@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private final ArtifactService artifacts;

    ArtifactController(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    /**
     * Uploads an artifact (issue #20 AC1-AC4): multipart
     * {@code file} plus the form fields {@code version_id},
     * {@code platform}, and the optional {@code build_number} a CI
     * uploader pins (S5-02/03 extract it otherwise). Returns 201 with
     * the registered view, {@code duplicateOf} included when the same
     * content already exists elsewhere in the application —
     * informational, never a rejection.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#versionId, 'version', 'app:artifacts:upload')")
    ArtifactView upload(
            @AuthenticationPrincipal Object principal,
            @RequestParam("version_id") UUID versionId,
            @RequestParam("platform") String platform,
            @RequestParam(value = "build_number", required = false) Long buildNumber,
            @RequestPart("file") MultipartFile file) {
        return artifacts.upload(requireUserId(principal), versionId, platform, originalName(file), buildNumber, file);
    }

    /**
     * Creates a direct-to-storage upload session (issue #20 AC5): the
     * guards of the multipart path run first, then the response hands
     * back the resumable session URL and the pre-allocated artifact id.
     * The client streams bytes to {@code uploadUrl} (PUT; one request
     * or resumable chunks) and then finalizes. Honors
     * {@code Idempotency-Key}.
     */
    @PostMapping("/upload-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.versionId(), 'version', 'app:artifacts:upload')")
    UploadSessionView createSession(
            @AuthenticationPrincipal Object principal, @RequestBody CreateSessionRequest request) {
        return artifacts.createSession(
                requireUserId(principal),
                request.versionId(),
                request.platform(),
                request.fileName(),
                request.buildNumber(),
                request.sizeHint());
    }

    /**
     * Finalizes a direct-to-storage upload (issue #20 AC5): the server
     * streams the stored object through a SHA-256 digest, registers the
     * artifact, and deletes the session. 201 on first registration,
     * 200 replaying the registered artifact on any retry — the state
     * is the replay answer. The body carries the version id the
     * session belongs to (the permission target); a mismatch is the
     * concealed miss.
     */
    @PostMapping("/{artifactId}/finalize")
    @PreAuthorize("hasPermission(#request.versionId(), 'version', 'app:artifacts:upload')")
    ResponseEntity<FinalizeView> finalizeSession(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID artifactId,
            @RequestBody FinalizeRequest request) {
        FinalizeView outcome = artifacts.finalizeSession(requireUserId(principal), artifactId, request.versionId());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome);
    }

    /**
     * Reads one artifact (issue #21, S5-02): the read-back surface of
     * the extraction tier — identity fields, lifecycle status, and
     * the extraction outcome (PENDING in an upload response by
     * construction; poll here to watch it settle). The {@code
     * version_id} query parameter carries the permission target for
     * the same reason the upload bodies do: the artifact collection
     * route is body-addressed, so no route interceptor can bind the
     * scope. A foreign artifact or a mismatched version is the
     * concealed miss.
     */
    @GetMapping("/{artifactId}")
    @PreAuthorize("hasPermission(#versionId, 'version', 'app:artifacts:read')")
    ArtifactView get(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID artifactId,
            @RequestParam("version_id") UUID versionId) {
        return artifacts.get(requireUserId(principal), artifactId, versionId);
    }

    private static String originalName(MultipartFile file) {
        return file == null ? null : file.getOriginalFilename();
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new ArtifactException(ArtifactException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The upload-session creation body (issue #20 AC5): everything the
     * multipart path's form carries, plus the optional {@code sizeHint}
     * (a client that knows the byte count up front gets the size
     * ceiling checked before the session is granted).
     */
    @Schema(
            description = "The direct-to-storage upload session to create: the version, platform, "
                    + "and file name of the artifact (extension allowlist .ipa/.apk/.aab applies), "
                    + "the optional CI-pinned build number, and the optional size hint in bytes.")
    public record CreateSessionRequest(
            UUID versionId, String platform, String fileName, Long buildNumber, Long sizeHint) {}

    /** The finalize body: the version the session belongs to. */
    @Schema(description = "The version id the finalized artifact belongs to.")
    public record FinalizeRequest(UUID versionId) {}
}
