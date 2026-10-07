package com.shipperdog.api.artifact;

import com.shipperdog.api.artifact.internal.download.DownloadTokenCodec;
import com.shipperdog.api.artifact.internal.download.DownloadTokenCodec.Grant;
import java.io.InputStream;
import java.util.Optional;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The emulator-mode download proxy (issue #38, S8-04, ADR-0003's
 * download-tier amendment): the backend route that stands in for GCS
 * V4 signing wherever the storage target cannot verify a signature —
 * the compose GCS emulator (fsouza/fake-gcs-server) of local and CI
 * deployments. Production never routes here: its issuance mints real
 * V4 URLs against GCS, and this controller is dead weight the same
 * build carries but no client is handed.
 *
 * <p><strong>The URL is the credential.</strong> The route is
 * anonymous by design ({@code permitAll} at the chain, declared here
 * with method security's own {@code permitAll()} so the posture is
 * visible at the handler, per the endpoint-security checklist): the
 * HMAC-SHA256 token in the path is the pre-authorization an engine
 * ALLOW minted at issuance time. Every failure — an unknown token, a
 * tampered payload, an expired grant, a missing object — answers the
 * SAME opaque 404 ({@code DOWNLOAD_NOT_FOUND}), byte-identical
 * across all of them: the fetcher learns neither which check failed
 * nor that an artifact exists (INV-005's no-leak rule, applied to the
 * one surface whose callers are anonymous by design). Guesses fail
 * closed: a token this deployment did not mint cannot verify.
 *
 * <p>The response streams the stored object ({@code
 * ArtifactStore.open} — the download tier's own read surface, chunked
 * at the store's bound, never buffered whole) as an {@code
 * attachment} carrying the artifact's file name, with the byte size
 * the grant recorded. No database read sits on this path: the token
 * carries everything the proxy serves from, so the URL stays
 * stateless end to end and revocation semantics live entirely at
 * issuance (the TTL bound) — exactly the property that makes the
 * emulator branch indistinguishable in contract from a V4 URL.
 */
@RestController
@RequestMapping("/api/v1/downloads")
public class DownloadProxyController {

    private final ArtifactStore store;
    private final DownloadTokenCodec tokens;

    DownloadProxyController(ArtifactStore store, DownloadTokenCodec tokens) {
        this.store = store;
        this.tokens = tokens;
    }

    /**
     * Streams one pre-authorized download: verify the token's MAC and
     * expiry, open the object, and answer the bytes. The uniform 404
     * of every failure leg is the contract — the caller cannot
     * distinguish an expired URL from a never-existed one.
     */
    @GetMapping("/{token}")
    @PreAuthorize("permitAll()")
    ResponseEntity<InputStreamResource> fetch(@PathVariable String token) {
        Optional<Grant> grant = tokens.parse(token);
        if (grant.isEmpty()) {
            throw downloadMissing();
        }
        InputStream bytes = store.open(grant.get().storageKey());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(
                        grant.get().fileName() == null
                                ? "artifact"
                                : grant.get().fileName())
                .build());
        headers.setContentLength(grant.get().size());
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(bytes));
    }

    private static ArtifactException downloadMissing() {
        return new ArtifactException(ArtifactException.Code.DOWNLOAD_NOT_FOUND, "download not found");
    }
}
