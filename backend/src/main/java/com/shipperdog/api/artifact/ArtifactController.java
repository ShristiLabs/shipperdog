package com.shipperdog.api.artifact;

import com.shipperdog.api.artifact.internal.download.ArtifactDownloadService;
import com.shipperdog.api.artifact.internal.download.ArtifactDownloadService.DownloadIssuanceView;
import com.shipperdog.api.artifact.internal.provenance.Provenance;
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
    private final ArtifactDownloadService downloads;

    ArtifactController(ArtifactService artifacts, ArtifactDownloadService downloads) {
        this.artifacts = artifacts;
        this.downloads = downloads;
    }

    /**
     * Uploads an artifact (issue #20 AC1-AC4; issue #23 provenance):
     * multipart {@code file} plus the form fields {@code version_id},
     * {@code platform}, the optional {@code build_number} a CI
     * uploader pins (S5-02/03 extract it otherwise), and the optional
     * build-provenance claims — {@code git_commit}, {@code git_branch},
     * {@code pipeline}, {@code ci_run_url}, {@code build_timestamp}
     * (S5-04, FR-005; the frozen names the S12-03/04 CI integrations
     * send). Provenance is optional (manual uploads may omit every
     * field) but shaped when present: a malformed commit or a non-URL
     * run link answers one 400 naming every offending field, and
     * nothing is ever fabricated server-side. Returns 201 with the
     * registered view, {@code duplicateOf} included when the same
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
            @RequestParam(value = "git_commit", required = false) String gitCommit,
            @RequestParam(value = "git_branch", required = false) String gitBranch,
            @RequestParam(value = "pipeline", required = false) String pipeline,
            @RequestParam(value = "ci_run_url", required = false) String ciRunUrl,
            @RequestParam(value = "build_timestamp", required = false) String buildTimestamp,
            @RequestPart("file") MultipartFile file) {
        return artifacts.upload(
                requireUserId(principal),
                versionId,
                platform,
                originalName(file),
                buildNumber,
                new Provenance.Fields(gitCommit, gitBranch, pipeline, ciRunUrl, buildTimestamp),
                file);
    }

    /**
     * Creates a direct-to-storage upload session (issue #20 AC5; issue
     * #23 provenance): the guards of the multipart path run first —
     * including the provenance validation, since this creation moment
     * IS the resumable surface's upload-time capture point; the
     * session carries the claims and finalize replays them into the
     * registered artifact (finalize itself takes no provenance: hours
     * may separate the calls, and the claims belong to the creating
     * client). Then the response hands back the resumable session URL
     * and the pre-allocated artifact id. The client streams bytes to
     * {@code uploadUrl} (PUT; one request or resumable chunks) and
     * then finalizes. Honors {@code Idempotency-Key}.
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
                new Provenance.Fields(
                        request.gitCommit(),
                        request.gitBranch(),
                        request.pipeline(),
                        request.ciRunUrl(),
                        request.buildTimestamp()),
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

    /**
     * Re-drives validation (issue #24, S5-05 — the story's retry
     * scope): a FAILED artifact moves back to VALIDATING and the
     * pipeline re-runs; a blocked validation or extraction re-enters
     * its tier where it stands. Deterministic stages reproduce their
     * verdict, but a corrected world — a fixed registry identifier,
     * recovered storage — wins through this retry. The {@code
     * version_id} query parameter is the permission target (the same
     * body-addressed shape as the read); anything not FAILED or
     * still-VALIDATING answers 409 {@code ARTIFACT_NOT_VALIDATABLE},
     * the PRD 18 table's own refusal. 202: the attempt is scheduled,
     * not awaited — read the artifact back to watch it settle.
     */
    @PostMapping("/{artifactId}/revalidate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasPermission(#versionId, 'version', 'app:artifacts:upload')")
    ArtifactView revalidate(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID artifactId,
            @RequestParam("version_id") UUID versionId) {
        return artifacts.revalidate(requireUserId(principal), artifactId, versionId);
    }

    /**
     * Issues one tester download (issue #38, S8-04, FR-014): the
     * layered engine's DOWNLOAD decision for the CALLER is this
     * route's entire authority — no console-grade {@code
     * app:artifacts:read} gate, the #37 follow-up's deliberate
     * relaxation (a pure tester holds no RBAC role by design, S6-03,
     * so a permission gate would exclude exactly the persona the
     * download serves; {@code isAuthenticated()} is the platform's
     * convention for caller-addressed surfaces, and the engine's
     * role-overlay entitlement still admits the console personas).
     * On ALLOW the answer carries the routed channel and behavior,
     * the short-lived signed URL, and its expiry — never the storage
     * key (signed URLs are the binary's only egress). A denial
     * renders the artifact read's own two concealed miss shapes,
     * byte-identical to the genuine ones; no URL is ever minted on
     * deny (INV-005). The {@code version_id} query parameter is the
     * same body-addressed permission target shape as the read.
     *
     * <p>Since S15-01 the answer also carries the telemetry block
     * for the tester telemetry SDK: the optional {@code tenant_id}
     * names the reporting tenant the token binds (the portal's
     * membership tenant — the version's owning tenant when absent),
     * and the optional {@code device_id} pairs the token with one of
     * the downloading tester's registered ACTIVE devices (the device
     * registry's two-switches revocation applies to a paired token).
     * Telemetry is additive evidence, never a download gate: the
     * block is simply absent when the pairing does not resolve.
     */
    @GetMapping("/{artifactId}/download")
    @PreAuthorize("isAuthenticated()")
    DownloadIssuanceView download(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID artifactId,
            @RequestParam("version_id") UUID versionId,
            @RequestParam(value = "tenant_id", required = false) UUID tenantId,
            @RequestParam(value = "device_id", required = false) UUID deviceId) {
        return downloads.issue(requireUserId(principal), artifactId, versionId, tenantId, deviceId);
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
     * The upload-session creation body (issue #20 AC5; issue #23
     * provenance): everything the multipart path's form carries,
     * including the optional build-provenance claims (the camelCase
     * JSON members of the frozen S5-04 field names), plus the optional
     * {@code sizeHint} (a client that knows the byte count up front
     * gets the size ceiling checked before the session is granted).
     */
    @Schema(
            description = "The direct-to-storage upload session to create: the version, platform, "
                    + "and file name of the artifact (extension allowlist .ipa/.apk/.aab applies), "
                    + "the optional CI-pinned build number, the optional size hint in bytes, and "
                    + "the optional client-asserted build provenance (gitCommit, gitBranch, pipeline, "
                    + "ciRunUrl, buildTimestamp: shape-validated claims, never verified facts).")
    public record CreateSessionRequest(
            UUID versionId,
            String platform,
            String fileName,
            Long buildNumber,
            String gitCommit,
            String gitBranch,
            String pipeline,
            String ciRunUrl,
            String buildTimestamp,
            Long sizeHint) {}

    /** The finalize body: the version the session belongs to. */
    @Schema(description = "The version id the finalized artifact belongs to.")
    public record FinalizeRequest(UUID versionId) {}
}
