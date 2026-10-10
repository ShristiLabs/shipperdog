package com.shipperdog.api.integration.internal.webhook;

import java.io.ByteArrayInputStream;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The App Store receiver's authenticity leg (issue #54, S12-04,
 * FR-022): Apple's App Store Server Notifications deliver a signed JWS
 * ({@code signedPayload}, per Apple's documented signature scheme) --
 * compact {@code header.payload.signature}, ES256, with the full
 * certificate chain carried in the header's {@code x5c}. Verification
 * is two independent checks, both of which must pass:
 *
 * <p><b>Chain</b> -- the x5c certificates form a valid path to one of
 * the configured trust anchors (Apple Root CA G3 by default, the
 * public root Apple publishes; loaded from the configured resources at
 * startup, fail-closed if they do not parse). PKIX validation with
 * revocation off: this is Apple's own documented posture for server
 * notifications, whose intermediates are expected to chain to the
 * pinned root.
 *
 * <p><b>Signature</b> -- the raw R||S (P-1363) ECDSA P-256 signature
 * over the ASCII {@code header.payload} bytes verifies under the leaf
 * certificate's public key.
 *
 * <p>The verdict is boolean by design: every failure (wrong shape,
 * wrong algorithm, broken chain, wrong root, tampered payload,
 * tampered signature) is one uniform "not accepted" -- the caller
 * answers its uniform leg and never learns WHICH check refused, the
 * same posture the shared-secret family's constant-time compare sets.
 */
@Component
public class AppleJwsVerifier {

    private static final Logger log = LoggerFactory.getLogger(AppleJwsVerifier.class);

    /** Apple's documented algorithm for server-notification payloads. */
    private static final String EXPECTED_ALG = "ES256";

    private final List<X509Certificate> trustedRoots;
    private final ObjectMapper mapper;

    public AppleJwsVerifier(List<X509Certificate> trustedRoots, ObjectMapper mapper) {
        this.trustedRoots = List.copyOf(trustedRoots);
        this.mapper = mapper;
    }

    /**
     * The authenticity verdict and, when it passes, the decoded
     * payload. Returns null for EVERY failure leg -- the caller's
     * uniform answer is the only public signal.
     */
    public JsonNode verifyAndDecode(String signedPayload) {
        if (signedPayload == null || signedPayload.isBlank()) {
            return null;
        }
        String[] parts = signedPayload.trim().split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            Base64.Decoder url = Base64.getUrlDecoder();
            JsonNode header = mapper.readTree(url.decode(parts[0]));
            JsonNode alg = header.get("alg");
            JsonNode x5c = header.get("x5c");
            if (alg == null || !EXPECTED_ALG.equals(alg.asText()) || x5c == null || !x5c.isArray() || x5c.isEmpty()) {
                return null;
            }
            List<X509Certificate> chain = parseChain(x5c);
            if (chain.isEmpty() || !chainIsValid(chain)) {
                return null;
            }
            byte[] signature = url.decode(parts[2]);
            byte[] signed = (parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(chain.get(0).getPublicKey());
            verifier.update(signed);
            if (!verifier.verify(signature)) {
                return null;
            }
            return mapper.readTree(url.decode(parts[1]));
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            log.debug(
                    "an appstore signedPayload failed verification: {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private static List<X509Certificate> parseChain(JsonNode x5c) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (JsonNode encoded : x5c) {
                Certificate certificate = factory.generateCertificate(
                        new ByteArrayInputStream(Base64.getDecoder().decode(encoded.asText())));
                if (certificate instanceof X509Certificate x509) {
                    chain.add(x509);
                }
            }
            return chain;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            return List.of();
        }
    }

    /**
     * PKIX path validation against the configured anchors. A chain
     * that includes an anchor itself has it filtered out first (PKIX
     * expects the path to END at, not include, the trust anchor), the
     * shape Apple's own x5c arrays arrive in.
     */
    private boolean chainIsValid(List<X509Certificate> chain) {
        try {
            Set<TrustAnchor> anchors = new java.util.HashSet<>();
            for (X509Certificate root : trustedRoots) {
                anchors.add(new TrustAnchor(root, null));
            }
            List<X509Certificate> path = new ArrayList<>(chain);
            path.removeIf(cert -> anchors.stream().anyMatch(anchor -> cert.equals(anchor.getTrustedCert())));
            if (path.isEmpty()) {
                return false;
            }
            PKIXParameters parameters = new PKIXParameters(anchors);
            parameters.setRevocationEnabled(false);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            CertPath certPath = factory.generateCertPath(path);
            CertPathValidator.getInstance("PKIX").validate(certPath, parameters);
            return true;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** Resource locations are rendered for startup diagnostics only (never contents). */
    @Override
    public String toString() {
        return "AppleJwsVerifier{trustedRoots=" + trustedRoots.size() + ", expectedAlg="
                + EXPECTED_ALG.toLowerCase(Locale.ROOT) + "}";
    }
}
