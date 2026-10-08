package api

import "encoding/json"

// View structs mirroring the OpenAPI baseline schemas. JSON member
// names are the served contract (docs/openapi/baseline.yaml); the
// provenance member names are the frozen S5-04 contract and never
// rename.

// MeResponse is GET /api/v1/auth/me.
type MeResponse struct {
	ID               string `json:"id"`
	Email            string `json:"email"`
	FullName         string `json:"fullName"`
	Status           string `json:"status"`
	OrgID            string `json:"orgId"`
	TenantID         string `json:"tenantId"`
	AuthenticatedVia string `json:"authenticatedVia"`
}

// Problem is the RFC 7807 error envelope every API error uses.
type Problem struct {
	Type    string            `json:"type"`
	Title   string            `json:"title"`
	Status  int               `json:"status"`
	Code    string            `json:"code"`
	Detail  string            `json:"detail"`
	TraceID string            `json:"trace_id"`
	Errors  map[string]string `json:"errors"`
}

// Error renders the problem for stderr output.
func (p *Problem) Error() string {
	if p.Code != "" && p.Detail != "" {
		return p.Code + ": " + p.Detail
	}
	if p.Code != "" {
		return p.Code
	}
	return p.Detail
}

// ProvenanceView is the frozen S5-04 provenance block.
type ProvenanceView struct {
	GitCommit      string `json:"gitCommit,omitempty"`
	GitBranch      string `json:"gitBranch,omitempty"`
	Pipeline       string `json:"pipeline,omitempty"`
	CiRunUrl       string `json:"ciRunUrl,omitempty"`
	BuildTimestamp string `json:"buildTimestamp,omitempty"`
}

// ExtractionView is the extraction tier on the artifact read.
type ExtractionView struct {
	Status       string `json:"status"`
	ErrorCode    string `json:"errorCode"`
	ErrorMessage string `json:"errorMessage"`
}

// ValidationStageView is one pipeline stage outcome, in FR-006 order.
type ValidationStageView struct {
	Name   string `json:"name"`
	Status string `json:"status"`
	Code   string `json:"code"`
	Reason string `json:"reason"`
}

// ValidationView is the validation block on the artifact read.
type ValidationView struct {
	Status     string                `json:"status"`
	Attempts   int32                 `json:"attempts"`
	DurationMs int64                 `json:"durationMs"`
	Stages     []ValidationStageView `json:"stages"`
}

// DuplicateOf is an informational same-content hit.
type DuplicateOf struct {
	ArtifactID string `json:"artifactId"`
	VersionID  string `json:"versionId"`
}

// ArtifactView is the artifact read (GET /api/v1/artifacts/{id}).
type ArtifactView struct {
	ID                   string          `json:"id"`
	VersionID            string          `json:"versionId"`
	Platform             string          `json:"platform"`
	FileName             string          `json:"fileName"`
	Size                 int64           `json:"size"`
	Sha256               string          `json:"sha256"`
	BuildNumber          *int64          `json:"buildNumber"`
	BundleID             string          `json:"bundleId"`
	VersionName          string          `json:"versionName"`
	MinimumOS            string          `json:"minimumOs"`
	Status               string          `json:"status"`
	UploadedBy           string          `json:"uploadedBy"`
	StorageKey           string          `json:"storageKey"`
	UploadedAt           string          `json:"uploadedAt"`
	Provenance           *ProvenanceView `json:"provenance"`
	Extraction           *ExtractionView `json:"extraction"`
	Format               string          `json:"format"`
	Validation           *ValidationView `json:"validation"`
	DuplicateOf          []DuplicateOf   `json:"duplicateOf"`
	DuplicateOfTruncated bool            `json:"duplicateOfTruncated"`
}

// CreateSessionRequest is POST /api/v1/artifacts/upload-sessions.
type CreateSessionRequest struct {
	VersionID      string `json:"versionId"`
	Platform       string `json:"platform"`
	FileName       string `json:"fileName"`
	BuildNumber    *int64 `json:"buildNumber,omitempty"`
	GitCommit      string `json:"gitCommit,omitempty"`
	GitBranch      string `json:"gitBranch,omitempty"`
	Pipeline       string `json:"pipeline,omitempty"`
	CiRunUrl       string `json:"ciRunUrl,omitempty"`
	BuildTimestamp string `json:"buildTimestamp,omitempty"`
	SizeHint       int64  `json:"sizeHint,omitempty"`
}

// UploadSessionView is the granted direct-to-storage session.
type UploadSessionView struct {
	SessionID  string `json:"sessionId"`
	ArtifactID string `json:"artifactId"`
	VersionID  string `json:"versionId"`
	UploadURL  string `json:"uploadUrl"`
	ExpiresAt  string `json:"expiresAt"`
}

// FinalizeView answers POST /api/v1/artifacts/{id}/finalize; created
// false means the retry replayed the already-registered artifact.
type FinalizeView struct {
	Created  bool          `json:"created"`
	Artifact *ArtifactView `json:"artifact"`
}

// ApplicationView is an application registry row.
type ApplicationView struct {
	ID             string   `json:"id"`
	OrganizationID string   `json:"organizationId"`
	TenantID       string   `json:"tenantId"`
	OwnershipScope string   `json:"ownershipScope"`
	Name           string   `json:"name"`
	Description    string   `json:"description"`
	Platforms      []string `json:"platforms"`
	BundleID       string   `json:"bundleId"`
	PackageName    string   `json:"packageName"`
	Status         string   `json:"status"`
	CreatedAt      string   `json:"createdAt"`
	UpdatedAt      string   `json:"updatedAt"`
}

// VersionArtifactSlot is an artifact as carried on a version view.
type VersionArtifactSlot struct {
	ID          string          `json:"id"`
	Platform    string          `json:"platform"`
	FileName    string          `json:"fileName"`
	Size        int64           `json:"size"`
	Sha256      string          `json:"sha256"`
	Status      string          `json:"status"`
	UploadedAt  string          `json:"uploadedAt"`
	BuildNumber *int64          `json:"buildNumber"`
	BundleID    string          `json:"bundleId"`
	VersionName string          `json:"versionName"`
	MinimumOS   string          `json:"minimumOs"`
	Extraction  string          `json:"extraction"`
	Provenance  *ProvenanceView `json:"provenance"`
	Format      string          `json:"format"`
	Validation  string          `json:"validation"`
}

// VersionView is a version row from the application's version listing.
type VersionView struct {
	ID                  string                           `json:"id"`
	ApplicationID       string                           `json:"applicationId"`
	VersionName         string                           `json:"versionName"`
	Status              string                           `json:"status"`
	Tags                []string                         `json:"tags"`
	CreatedAt           string                           `json:"createdAt"`
	UpdatedAt           string                           `json:"updatedAt"`
	ArtifactsByPlatform map[string][]VersionArtifactSlot `json:"artifactsByPlatform"`
}

// VersionDetail is the single-version read (adds release history).
type VersionDetail struct {
	VersionView
	ReleaseHistory []map[string]any `json:"releaseHistory"`
}

// Page shapes: `items` + `next_cursor` (the platform's one snake_case
// exception), per docs/api-conventions.md.

type ApplicationPage struct {
	Items      []ApplicationView `json:"items"`
	NextCursor string            `json:"next_cursor"`
}

type VersionPage struct {
	Items      []VersionView `json:"items"`
	NextCursor string        `json:"next_cursor"`
}

// ReleaseView is a release row from the application's release listing.
type ReleaseView struct {
	ID            string `json:"id"`
	ReleaseRef    string `json:"releaseRef"`
	ApplicationID string `json:"applicationId"`
	VersionID     string `json:"versionId"`
	Status        string `json:"status"`
	Ring          string `json:"ring"`
	Environment   string `json:"environment"`
	TargetCount   int    `json:"targetCount"`
	CreatedAt     string `json:"createdAt"`
	UpdatedAt     string `json:"updatedAt"`
}

// ReleaseArtifactSlot is one per-platform artifact selection of a release.
type ReleaseArtifactSlot struct {
	Platform   string `json:"platform"`
	ArtifactID string `json:"artifactId"`
}

// ReleaseTarget is one release target: the tenant, its ring, and its
// tester-group selection (empty groups target the tenant at large).
type ReleaseTarget struct {
	TenantID       string   `json:"tenantId"`
	Ring           string   `json:"ring"`
	TesterGroupIDs []string `json:"testerGroupIds"`
}

// ReleaseDetail is the release read: the full creation record with the
// per-platform artifact selection and the complete target set.
type ReleaseDetail struct {
	ID              string                `json:"id"`
	ReleaseRef      string                `json:"releaseRef"`
	ApplicationID   string                `json:"applicationId"`
	VersionID       string                `json:"versionId"`
	Status          string                `json:"status"`
	Ring            string                `json:"ring"`
	RingName        string                `json:"ringName"`
	EnvironmentID   string                `json:"environmentId"`
	Environment     string                `json:"environment"`
	EnvironmentName string                `json:"environmentName"`
	Artifacts       []ReleaseArtifactSlot `json:"artifacts"`
	Targets         []ReleaseTarget       `json:"targets"`
	CreatedBy       string                `json:"createdBy"`
	CreatedAt       string                `json:"createdAt"`
	UpdatedAt       string                `json:"updatedAt"`
}

// ReleasePage is the release listing's page shape.
type ReleasePage struct {
	Items      []ReleaseView `json:"items"`
	NextCursor string        `json:"next_cursor"`
}

// CreateReleaseRequest is POST /api/v1/releases (FR-015): the
// application id rides the body (the permission target), the version,
// the starting ring's code, the optional target environment, one READY
// artifact per platform, and the target set.
type CreateReleaseRequest struct {
	ApplicationID string                 `json:"applicationId"`
	VersionID     string                 `json:"versionId"`
	Ring          string                 `json:"ring"`
	EnvironmentID string                 `json:"environmentId,omitempty"`
	Artifacts     []ReleaseArtifactSlot  `json:"artifacts"`
	Targets       []ReleaseTargetCommand `json:"targets"`
}

// ReleaseTargetCommand is one target of the creation body.
type ReleaseTargetCommand struct {
	TenantID       string   `json:"tenantId"`
	TesterGroupIDs []string `json:"testerGroupIds"`
}

// ReleaseTransitionRequest is POST /api/v1/releases/{id}/transitions:
// the permission-target application id, the PRD 17 target state, and
// the reason (mandatory on the FAILED and ROLLED_BACK edges).
type ReleaseTransitionRequest struct {
	ApplicationID string `json:"applicationId"`
	To            string `json:"to"`
	Reason        string `json:"reason,omitempty"`
}

// ReleasePromoteRequest is POST /api/v1/releases/{id}/promote: the
// permission-target application, the optional selective tenant set
// (absent moves every target), and the optional asserted target ring
// (the effective order's next ring - anything farther is the skip
// refusal).
type ReleasePromoteRequest struct {
	ApplicationID string   `json:"applicationId"`
	Tenants       []string `json:"tenants,omitempty"`
	To            string   `json:"to,omitempty"`
}

// ReleasePromotionResult is the promote act's answer: the hop's two
// rings, the tenants moved, whether the hop carried the release onto
// PRD 17's PROMOTED crossing, and the refreshed per-target rings.
type ReleasePromotionResult struct {
	ReleaseID         string            `json:"releaseId"`
	ReleaseRef        string            `json:"releaseRef"`
	Status            string            `json:"status"`
	FromRing          string            `json:"fromRing"`
	ToRing            string            `json:"toRing"`
	Tenants           []string          `json:"tenants"`
	CrossedToPromoted bool              `json:"crossedToPromoted"`
	PromotionID       string            `json:"promotionId"`
	PromotionStatus   string            `json:"promotionStatus"`
	Blockers          map[string]string `json:"blockers"`
	Targets           []PromotionTarget `json:"targets"`
}

// PromotionTarget is one target's refreshed ring after a hop.
type PromotionTarget struct {
	TenantID string `json:"tenantId"`
	Ring     string `json:"ring"`
}

// ReleasePromotionPrecheck is GET /api/v1/releases/{id}/promotion-
// precheck: the SAME evaluation the promote act runs, answered as a
// read - the verdict, the complete blocker list (byte-for-byte the
// 409's errors member), the target ring's gate states, the approval
// leg's standing, and the widened eligibility rows.
type ReleasePromotionPrecheck struct {
	Promotable  bool                   `json:"promotable"`
	Blockers    map[string]string      `json:"blockers"`
	Gates       []PrecheckGateState    `json:"gates"`
	Approval    PrecheckApproval       `json:"approval"`
	Eligibility []PrecheckAdmissionRow `json:"eligibility"`
}

// PrecheckGateState is one gate of the target ring's evaluation.
type PrecheckGateState struct {
	GateType         string   `json:"gateType"`
	Required         bool     `json:"required"`
	Status           string   `json:"status"`
	ObservedValue    *float64 `json:"observedValue"`
	Threshold        *float64 `json:"threshold"`
	EvidenceURL      string   `json:"evidenceUrl"`
	Source           string   `json:"source"`
	Overridden       bool     `json:"overridden"`
	OverriddenBy     string   `json:"overriddenBy"`
	OverriddenReason string   `json:"overriddenReason"`
}

// PrecheckApproval is the approval leg's standing (NOT_REQUIRED,
// SATISFIED, PENDING, REJECTED, NOT_OPEN, UNRESOLVED).
type PrecheckApproval struct {
	Requirement string            `json:"requirement"`
	PolicyName  string            `json:"policyName"`
	RoundNumber *int64            `json:"roundNumber"`
	RoundStatus string            `json:"roundStatus"`
	WaitedOn    map[string]string `json:"waitedOn"`
}

// PrecheckAdmissionRow is one target's eligibility row against the
// target ring (the widening seam's own answer).
type PrecheckAdmissionRow struct {
	TenantID       string   `json:"tenantId"`
	Ring           string   `json:"ring"`
	Admitted       bool     `json:"admitted"`
	AudienceGroups []string `json:"audienceGroups"`
	TenantAtLarge  bool     `json:"tenantAtLarge"`
	Reasons        []string `json:"reasons"`
}

// ReleasePromotionHistory is GET /api/v1/releases/{id}/promotions: the
// append-only timeline of hops and refused attempts.
type ReleasePromotionHistory struct {
	ReleaseID  string                `json:"releaseId"`
	ReleaseRef string                `json:"releaseRef"`
	Status     string                `json:"status"`
	Promotions []ReleasePromotionRow `json:"promotions"`
}

// ReleasePromotionRow is one timeline row: a COMPLETED hop or a
// BLOCKED attempt with its recorded evidence.
type ReleasePromotionRow struct {
	ID         string            `json:"id"`
	FromRing   string            `json:"fromRing"`
	ToRing     string            `json:"toRing"`
	Tenants    []string          `json:"tenants"`
	Status     string            `json:"status"`
	Blockers   map[string]string `json:"blockers"`
	ActorID    string            `json:"actorId"`
	OccurredAt string            `json:"occurredAt"`
}

// ReleaseManifestView is GET /api/v1/releases/{id}/manifest: the
// storage metadata (digest, freeze facts) beside the stored PRD 16
// document, kept verbatim for --json output.
type ReleaseManifestView struct {
	ReleaseID     string          `json:"releaseId"`
	ReleaseRef    string          `json:"releaseRef"`
	ApplicationID string          `json:"applicationId"`
	VersionID     string          `json:"versionId"`
	Sha256        string          `json:"sha256"`
	FrozenAt      string          `json:"frozenAt"`
	FrozenBy      string          `json:"frozenBy"`
	Manifest      json.RawMessage `json:"manifest"`
}
