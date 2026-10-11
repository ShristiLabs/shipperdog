# ADR-0009: SAML Response validation is a scoped in-house layer over Apache Santuario's XML-DSig — never a hand-rolled signature path, never a full SAML object framework

Date: 2026-10-11
Status: Accepted (issue #68, S14-02)

## Context

S14-02 makes ShipperDog a SAML 2.0 service provider: the POST-binding
Assertion Consumer Service must decide, on the strength of the
payload's XML-DSig signature alone, whether a corporate identity may
mint a ShipperDog session. That decision is the platform's most
attack-exposed surface since the login itself — the SAML ecosystem's
known failure modes (signature wrapping, XXE, algorithm downgrade,
embedded-certificate trust, audience/recipient confusion) all live in
this exact code — and PRD 25's technical notes ask for "maintained
libraries" while the story demands the Entra, Okta, and Google
Workspace provider shapes.

The options considered:

1. **OpenSAML (the Shibboleth stack).** The canonical Java SAML
   library: full object model, provider profiles, metadata machinery.
   Rejected for this platform: a heavyweight dependency graph pulling
   a transitive version churn that fights the Spring Boot BOM (the
   issue's own note flags the version-management risk), a history of
   licensing and maintenance transitions that make it a fragile pin
   for an OSS project's long tail, and a surface area far beyond the
   SP role ShipperDog needs — every class kept on the classpath is
   attack surface that must be kept current.
2. **Hand-rolled XML-DSig.** No new dependencies; total control.
   Rejected outright: XML signature verification (canonicalization,
   transforms, digest computation, key transport) is precisely where
   "we wrote it ourselves" becomes a vulnerability class. The #22
   precedent (dd-plist for binary plist parsing) applies a fortiori:
   security-critical parsing and crypto belong to proven libraries.
3. **A scoped in-house SAML layer over Apache Santuario
   (`org.apache.santuario:xmlsec`).** Santuario is the proven,
   Apache-2.0, actively maintained XML-DSig implementation (the
   signature engine other stacks build on); the SAML protocol
   semantics around it — what to require of a Response, an Assertion,
   a Subject, Conditions — are a small, spec-readable surface this
   platform pins in one package and tests against spec-shaped attack
   fixtures.

## Decision

**Option 3: the `internal/saml` package validates everything; Santuario
verifies signatures.** `SamlResponseValidator` owns every check and
calls Santuario's `XMLSignature` only for the cryptographic verdict,
under semantics stricter than the library's defaults:

1. **XXE hardening at one seam** (`SamlXml`): DTDs disallowed outright,
   external general and parameter entities off, XInclude off, entity
   expansion off, secure processing on, and a 256 KiB size cap — every
   SAML document (ingested IdP metadata and inbound responses) parses
   through this seam and nowhere else.
2. **Certificate pinning from ingested IdP metadata ONLY**: the
   `use="signing"` (or unspecified-use) KeyDescriptor certificates of
   the organization's uploaded EntityDescriptor are the only trust
   anchors; a certificate embedded in the assertion's KeyInfo is
   ignored entirely. Multiple pinned certificates are kept (rotation
   overlap: any of them may verify).
3. **Wrap-attack resistance in layers**: document-wide SAML ID
   uniqueness refuses the classic duplicate-ID payload whole; the
   Response must carry exactly one Assertion as a DIRECT child; and
   every signature's single Reference must carry the `#ID` of its own
   ENCLOSING element — a signature covering any other element does not
   count, and `IdResolver` registers only the enclosing element's ID so
   the reference resolves to it.
4. **Algorithm pinning**: RSA-SHA256/384/512 and ECDSA-SHA256/384/512
   signature methods over SHA-256/384/512 digests only — SHA-1, HMAC
   (key confusion), and anything else is refused before the crypto
   runs. Transforms are pinned to enveloped-signature and exclusive
   c14n (no XPath/XSLT expression evaluators).
5. **Conditions semantics**: NotBefore/NotOnOrAfter within the
   configured clock skew, an AudienceRestriction naming this SP's
   entityID, and a bearer SubjectConfirmationData whose Recipient is
   this deployment's ACS with its own NotOnOrAfter respected;
   InResponseTo consistency between the Response and its confirmation
   data, with the SP-initiated single-use consume the login service's
   to decide. Transient NameIDs are refused (single-use by
   definition); EncryptedAssertion is refused and deferred — this SP
   publishes no encryption KeyDescriptor, so no honest IdP encrypts
   for it.

The provider-shape variance the story names is configuration, not
code: `wantAssertionsSigned` (Entra: assertion-signed) and
`wantResponseSigned` (Google Workspace: response-signed only) are
per-organization policy with at least one required — the API refuses
both-false.

## Consequences

- One dependency enters the tree: `xmlsec` (Apache-2.0, maintained),
  pinned in `backend/pom.xml` beside the platform's other
  security-critical pins. The SAML object layer is deliberately
  in-house and small (~1000 lines including metadata SP generation);
  its whole surface is exercised by the ssofixture `MockSamlIdp`
  attack fixtures — unsigned legs, foreign certificate, wrong
  audience, expired and not-yet-valid windows, SHA-1 downgrade, XPath
  transform, the duplicate-ID wrap payload, and a DTD-bearing XXE
  attempt — each pinned to its distinct `saml_*` audit reason.
- The mock IdP signs with the same Santuario stack the platform
  verifies with, so the fixtures exercise the real cryptographic
  interop, not a bypass; a real-tenant smoke against Entra/Okta
  developer tenants remains a deployment-time checklist item (the
  mock-first CI discipline keeps no live IdP in the pipeline).
- IdP metadata rotation is a configuration write: the new certificate
  pins when the metadata is re-uploaded, the old one stops verifying
  (refused as `saml_signature`), and validation failures WARN-log and
  audit (`SSO_LOGIN_FAILURE` with the `saml_*` reason) — the alerting
  surface for a rotation that has not been propagated.
- EncryptedAssertion and SAML Single Logout are documented follow-ups
  (SLO for SAML would join the OIDC back-channel logout doctrine when
  asked for); neither is a gap in the accepted scope.
- If a future SAML need outgrows the in-house layer (profiles this
  platform refuses today, artifact resolution, SLO), this ADR is the
  record to revisit — the trade to OpenSAML or a successor should be
  a conscious decision, not dependency creep.
