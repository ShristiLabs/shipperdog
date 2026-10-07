package com.shipperdog.api.artifact.internal.download;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.internal.download.DownloadTokenCodec.Grant;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The emulator-mode download token's contract (issue #38, S8-04): a
 * minted token parses back to exactly its grant while unexpired, and
 * EVERY failure leg — tampering with the payload, a foreign signature,
 * a past expiry, malformed shapes — parses to nothing, so the proxy
 * answers one uniform opaque 404 for all of them (INV-005's no-leak
 * rule applied to the anonymous surface).
 */
class DownloadTokenCodecTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    private final DownloadTokenCodec codec =
            new DownloadTokenCodec(new ArtifactDownloadProperties(Duration.ofMinutes(15), "test-key"), CLOCK);

    @Test
    void aMintedTokenParsesBackToExactlyItsGrant() {
        Grant grant = new Grant(
                UUID.randomUUID(),
                "org/tenant/app/version/artifact/artifact",
                "banking.apk",
                4096,
                Instant.now(CLOCK).plusSeconds(90));
        String token = codec.mint(grant);
        assertThat(codec.parse(token)).contains(grant);
        assertThat(token)
                .as("the token is opaque — no storage key in the clear")
                .doesNotContain("org/tenant");
    }

    @Test
    void aTamperedPayloadNeverParses() {
        Grant grant =
                new Grant(UUID.randomUUID(), "k", "a.apk", 1, Instant.now(CLOCK).plusSeconds(90));
        String token = codec.mint(grant);
        String tampered = "x" + token.substring(1);
        assertThat(codec.parse(tampered)).isEmpty();
    }

    @Test
    void anExpiredTokenNeverParses() {
        Grant expired =
                new Grant(UUID.randomUUID(), "k", "a.apk", 1, Instant.now(CLOCK).plusSeconds(1));
        String token = codec.mint(expired);
        Clock later = Clock.fixed(Instant.parse("2026-10-08T12:00:02Z"), ZoneOffset.UTC);
        DownloadTokenCodec laterCodec =
                new DownloadTokenCodec(new ArtifactDownloadProperties(Duration.ofMinutes(15), "test-key"), later);
        assertThat(laterCodec.parse(token)).isEmpty();
    }

    @Test
    void malformedShapesNeverParse() {
        assertThat(codec.parse(null)).isEmpty();
        assertThat(codec.parse("")).isEmpty();
        assertThat(codec.parse("not-a-token")).isEmpty();
        assertThat(codec.parse(".")).isEmpty();
        assertThat(codec.parse("a.b.c")).isEmpty();
        assertThat(codec.parse(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void aTokenMintedUnderAnotherKeyNeverParses() {
        Grant grant =
                new Grant(UUID.randomUUID(), "k", "a.apk", 1, Instant.now(CLOCK).plusSeconds(90));
        String foreign = new DownloadTokenCodec(
                        new ArtifactDownloadProperties(Duration.ofMinutes(15), "another-key"), CLOCK)
                .mint(grant);
        assertThat(codec.parse(foreign)).isEmpty();
    }

    /**
     * The binding the dispatch names: a captured token cannot be
     * repointed at another artifact or another stored object. The
     * payload is base64url — readable to anyone — so the attack is to
     * rewrite one field (the artifact id, or the storage key) and keep
     * the original signature: the MAC covers every field, so both
     * forgeries fail. The control leg (the same re-encode UNTOUCHED)
     * proves the harness itself is not what breaks the MAC.
     */
    @Test
    void aTokenCannotBeRepointedAtAnotherArtifactOrObject() {
        Grant grant = new Grant(
                UUID.randomUUID(),
                "org/tenant/app/version/artifact-a/artifact",
                "a.apk",
                1,
                Instant.now(CLOCK).plusSeconds(90));
        String token = codec.mint(grant);

        // Control: re-encoding the payload unchanged keeps the token valid.
        assertThat(codec.parse(withPayloadField(token, 1, grant.artifactId().toString())))
                .isPresent();

        // Repointing the artifact id: the attribution changes, the MAC does not.
        assertThat(codec.parse(withPayloadField(token, 1, UUID.randomUUID().toString())))
                .isEmpty();
        // Repointing the storage key: the object the proxy would open changes.
        assertThat(codec.parse(withPayloadField(token, 5, "org/tenant/app/version/artifact-b/artifact"))
                        .isEmpty())
                .isTrue();
        // Inflating the size field (the served Content-Length) equally fails.
        assertThat(codec.parse(withPayloadField(token, 3, "999999999"))).isEmpty();
    }

    /** Re-encodes the payload with one field replaced, keeping the original signature. */
    private static String withPayloadField(String token, int field, String replacement) {
        int separator = token.indexOf('.');
        byte[] payload = Base64.getUrlDecoder().decode(token.substring(0, separator));
        String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
        fields[field] = replacement;
        byte[] forged = String.join("|", fields).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(forged) + token.substring(separator);
    }
}
