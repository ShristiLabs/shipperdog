package com.shipperdog.api.integration.internal.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The App Store JWS verifier's unit legs (issue #54, S12-04): a
 * well-formed ES256 signedPayload whose x5c chains to the trusted
 * root decodes; every tampered, mis-chained, or mis-shapen variant is
 * the one uniform refusal. The fixtures are self-signed ShipperDog
 * TEST certificates (generated for this suite, never credentials --
 * the name says so), standing in for Apple's chain; production trusts
 * Apple Root CA G3, the public root.
 */
class AppleJwsVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static X509Certificate root;
    private static X509Certificate leaf;
    private static PrivateKey leafKey;
    private static X509Certificate untrustedRoot;
    private static X509Certificate untrustedLeaf;
    private static PrivateKey untrustedLeafKey;
    private static AppleJwsVerifier verifier;
    private static AppleJwsVerifier wrongRootVerifier;

    @BeforeAll
    static void loadFixtures() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        root = certificate(factory, "webhooks/appstore-test-root.crt");
        leaf = certificate(factory, "webhooks/appstore-test-leaf.crt");
        leafKey = ecKey("webhooks/appstore-test-leaf.key");
        // A fully valid chain to a DIFFERENT root (the stand-in for
        // "someone else's CA"): well-formed, well-signed, and refused
        // because it does not reach the trusted anchor.
        untrustedRoot = certificate(factory, "webhooks/untrusted-root.crt");
        untrustedLeaf = certificate(factory, "webhooks/untrusted-leaf.crt");
        untrustedLeafKey = ecKey("webhooks/untrusted-leaf.key");
        // The "wrong root" verifier trusts the LEAF itself as an
        // anchor: nothing chains to it, so every delivery fails the
        // chain leg -- the stand-in for "not Apple's chain".
        wrongRootVerifier = new AppleJwsVerifier(List.of(leaf), MAPPER);
        verifier = new AppleJwsVerifier(List.of(root), MAPPER);
    }

    @Test
    void wellFormedSignedPayloadDecodes() {
        ObjectNode payload = basePayload();
        String jws = signed(payload, "ES256", List.of(leaf, root));
        JsonNode decoded = verifier.verifyAndDecode(jws);
        assertThat(decoded).isNotNull();
        assertThat(decoded.get("notificationType").asString()).isEqualTo("SUBSCRIBED");
        assertThat(decoded.get("notificationUUID").asString()).isEqualTo("fixture-uuid-1");
    }

    @Test
    void everyTamperedOrMisShapenVariantRefusesUniformly() {
        ObjectNode payload = basePayload();
        String good = signed(payload, "ES256", List.of(leaf, root));
        // The genuine payload-swap: the ORIGINAL header and signature
        // with a DIFFERENT payload's bytes between them (the signature
        // covers header.payload, so the swap must not verify).
        String payloadSwap = good.split("\\.")[0]
                + "."
                + signed(otherPayload(), "ES256", List.of(leaf, root)).split("\\.")[1]
                + "."
                + good.split("\\.")[2];
        for (String variant : List.of(
                payloadSwap,
                // A well-formed chain to the WRONG root (someone
                // else's CA: refused at the chain leg).
                signed(untrustedLeafKey, payload, "ES256", List.of(untrustedLeaf, untrustedRoot)),
                // A signature that does not match the leaf the x5c
                // presents (the key-cert mismatch leg).
                signed(untrustedLeafKey, payload, "ES256", List.of(leaf, root)),
                // The wrong algorithm family.
                signed(payload, "HS256", List.of(leaf, root)),
                // Broken compact shapes.
                "",
                "not-a-jws",
                good.substring(0, good.length() - 8) + "AAAAAA")) {
            assertThat(verifier.verifyAndDecode(variant))
                    .as("variant [%.24s]", variant)
                    .isNull();
        }
    }

    @Test
    void aChainToTheWrongRootRefuses() {
        String jws = signed(basePayload(), "ES256", List.of(leaf, root));
        assertThat(wrongRootVerifier.verifyAndDecode(jws)).isNull();
    }

    @Test
    void tamperedSignatureRefuses() throws Exception {
        String good = signed(basePayload(), "ES256", List.of(leaf, root));
        String[] parts = good.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 0x01;
        String tampered = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        assertThat(verifier.verifyAndDecode(tampered)).isNull();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static ObjectNode basePayload() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("notificationType", "SUBSCRIBED");
        payload.put("notificationUUID", "fixture-uuid-1");
        payload.put("signedDate", "1760000000000");
        payload.putObject("data").put("bundleId", "com.example.app");
        return payload;
    }

    private static ObjectNode otherPayload() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("notificationType", "DID_CHANGE_RENEWAL_STATUS");
        payload.put("notificationUUID", "fixture-uuid-2");
        payload.put("signedDate", "1760000000000");
        payload.putObject("data").put("bundleId", "com.example.app");
        return payload;
    }

    /** Apple's compact shape, signed by the trusted leaf's key (the ordinary sender). */
    private static String signed(ObjectNode payload, String alg, List<X509Certificate> chain) {
        return signed(leafKey, payload, alg, chain);
    }

    /** Apple's compact shape over an explicit signer (the adversarial legs choose their own key). */
    private static String signed(PrivateKey signerKey, ObjectNode payload, String alg, List<X509Certificate> chain) {
        try {
            Base64.Encoder x5c = Base64.getEncoder();
            StringBuilder certificateArray = new StringBuilder("[");
            for (X509Certificate certificate : chain) {
                if (certificateArray.length() > 1) {
                    certificateArray.append(',');
                }
                certificateArray
                        .append('"')
                        .append(x5c.encodeToString(certificate.getEncoded()))
                        .append('"');
            }
            certificateArray.append(']');
            String header = "{\"alg\":\"" + alg + "\",\"x5c\":" + certificateArray + "}";
            Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
            String encodedHeader = url.encodeToString(header.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = url.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(signerKey);
            signer.update((encodedHeader + "." + encodedPayload).getBytes(StandardCharsets.US_ASCII));
            String encodedSignature = url.encodeToString(signer.sign());
            return encodedHeader + "." + encodedPayload + "." + encodedSignature;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate certificate(CertificateFactory factory, String resource) throws Exception {
        try (InputStream in = AppleJwsVerifierTest.class.getClassLoader().getResourceAsStream(resource)) {
            Certificate certificate = factory.generateCertificate(in);
            return (X509Certificate) certificate;
        }
    }

    private static PrivateKey ecKey(String resource) throws Exception {
        String pem = Files.readString(Path.of(AppleJwsVerifierTest.class
                .getClassLoader()
                .getResource(resource)
                .toURI()));
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
