-- V16__artifact_extraction.sql
-- Extracted-artifact metadata columns for iOS IPA parsing (issue #21,
-- S5-02, FR-005, PRD 8.5).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): five nullable
--     columns are added to artifacts; nothing is dropped, no constraint
--     is tightened, existing rows keep their meaning untouched.
--   * Constraint names use the explicit ck_/idx_ prefixes where added.
--
-- What the story demands and this migration provides:
--
--   * FR-005 splits artifact metadata into two tiers. The fields S5-05
--     validation matching compares mechanically (bundle identifier,
--     version name, minimum OS, build number) become FIRST-CLASS
--     COLUMNS so they are constrainable, indexable, and queryable
--     without jsonb path expressions; build_number already exists
--     (V15 made it nullable for exactly this story — extraction fills
--     it when the uploader did not pin it).
--   * Everything else the parser lifts out of the IPA (display name,
--     signing information, entitlements summary, parser attribution,
--     timings) is open-ended and platform-specific — Android's S5-03
--     sibling will add package/versionCode/SDK levels, not iOS bundle
--     ids. That tier lands in ONE jsonb document per artifact
--     (extracted_metadata), shaped by the artifact module, never
--     constrainable from SQL — the same split the audit framework
--     chose for machine-readable context.
--   * extraction_error: the actionable failure record (issue #21 AC3).
--     A malformed or corrupt IPA must leave a VISIBLE, specific error
--     — not a silent VALIDATING row and not a deleted upload. The
--     artifact stays stored and immutable (INV-003); its status moves
--     to FAILED while this document carries the machine-readable code
--     and the operator-readable message.
--   * All five columns are nullable by design: an artifact registered
--     before this story (or mid-extraction) has extracted nothing yet;
--     NULL is the "extraction has not produced this value" state, and
--     the three scalar columns stay NULL together with the jsonb
--     documents until the parser writes them once.

-- ---------------------------------------------------------------------------
-- Artifacts: extraction scalar columns (the S5-05 matching tier)
-- ---------------------------------------------------------------------------

-- CFBundleIdentifier, e.g. com.acme.banking (iOS) or the Android
-- package name from S5-03; the identifier the application registry
-- matches builds against. Free-form case-sensitive string exactly as
-- extracted — Apple allows alphanumerics, hyphens, and dots, so no
-- format CHECK is imposed (the parser validates shape in code).
ALTER TABLE artifacts ADD COLUMN bundle_id varchar(255);

-- CFBundleShortVersionString (iOS) / versionName (Android, S5-03): the
-- marketing version string, NOT the numeric build. varchar because
-- Apple versions are strings by definition ("7.12.0", "1.0.0-beta.1").
ALTER TABLE artifacts ADD COLUMN version_name varchar(255);

-- MinimumOSVersion (iOS) / minSdkVersion (Android, S5-03): the OS floor
-- the binary requires, as the platform spells it ("15.0", "26"). Kept
-- as a string deliberately — numeric encoding would invent a versioning
-- scheme Apple does not promise to keep two-component.
ALTER TABLE artifacts ADD COLUMN minimum_os varchar(32);

-- ---------------------------------------------------------------------------
-- Artifacts: extraction documents (the open-ended tier) and the
-- failure record
-- ---------------------------------------------------------------------------

-- The full extraction payload written ONCE on success: display name,
-- signing information and entitlements summary lifted from the embedded
-- provisioning profile (fields stored only — verification is S5-05),
-- the raw build string when CFBundleVersion is not a plain integer,
-- parser attribution, and timings. Shaped by the artifact module's
-- ExtractedMetadata document; never a query target.
ALTER TABLE artifacts ADD COLUMN extracted_metadata jsonb;

-- The failure record (AC3): a small jsonb document with a stable code
-- (NOT_A_ZIP, MISSING_INFO_PLIST, MALFORMED_PLIST, ...), a
-- human-actionable message, and the moment extraction failed. Written
-- when parsing proves the binary malformed (the artifact then moves to
-- FAILED) or when extraction could not run at all (queue saturation,
-- storage unavailable after retries — the artifact then STAYS
-- VALIDATING; the document is what makes the missed extraction
-- visible instead of silent).
ALTER TABLE artifacts ADD COLUMN extraction_error jsonb;
