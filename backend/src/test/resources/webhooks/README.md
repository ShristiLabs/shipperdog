# Webhook receiver test fixtures

Self-signed TEST key material for the S12-04 store-receiver suites
(issue #54): stand-ins for Apple's certificate chain and Google's
JWKS signing key. These are NOT credentials -- they were generated
for this repository's test suite, sign nothing real, and are named
"ShipperDog ... Fixture" so no scanner or reader mistakes them for
secrets. Production trusts only the public Apple Root CA G3 bundled
under `src/main/resources/webhooks/` and Google's real JWKS
endpoint.

| File | Role |
|---|---|
| `appstore-test-root.crt` / `.key` | The trusted root of the App Store test chain (the Apple Root CA G3 stand-in) |
| `appstore-test-leaf.crt` / `.key` | The signing leaf of the same chain (signs the fixture signedPayload JWS) |
| `untrusted-root.crt`, `untrusted-leaf.crt` / `.key` | A fully valid chain to a DIFFERENT root -- the "someone else's CA" refusal leg |
| `google-play-test-rsa.crt` / `.key` | The RSA signer whose public half is served by the tests' local JWKS stand-in |

Regenerate with openssl (EC P-256 roots/leaves, 2048-bit RSA, PKCS8
PEM keys) if they ever expire; the certificates are valid for 100
years by construction.
