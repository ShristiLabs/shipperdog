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

// The OTA surface (S16-14, issue #176; ADR-0010): the bundle authoring
// draft (the create answer carries the direct-to-storage grant - D5),
// the SIGNED bundle the finalize lands, the exactly-once sdc1. app
// token (D3), the release's staged rollout plan (D6), and the
// dashboard funnel. Member names are the served baseline contract.

// CreateOtaBundleRequest is POST /api/v1/ota/bundles: the owning
// application, the semver-triple bundleVersion labeling the bundle's
// dedicated versions row (D2), the open-vocabulary platformTag, the
// bare fileName of the bundle archive, and the per-bundle forced/
// minAppVersion defaults (D7).
type CreateOtaBundleRequest struct {
	ApplicationID string `json:"applicationId"`
	BundleVersion string `json:"bundleVersion"`
	PlatformTag   string `json:"platformTag"`
	FileName      string `json:"fileName"`
	Forced        *bool  `json:"forced,omitempty"`
	MinAppVersion string `json:"minAppVersion,omitempty"`
	SizeHint      int64  `json:"sizeHint,omitempty"`
}

// OtaUploadGrantView is the direct-to-storage grant the create answer
// carries (the client streams the bundle's bytes to uploadUrl, as-is,
// without the API bearer header).
type OtaUploadGrantView struct {
	ArtifactID string `json:"artifactId"`
	UploadURL  string `json:"uploadUrl"`
	ExpiresAt  string `json:"expiresAt"`
}

// OtaDraftView is the create answer: the draft's identity (the bundle
// id IS the pre-allocated artifact id - the bytes are the bundle), its
// dedicated versions row, and the upload grant.
type OtaDraftView struct {
	ID            string              `json:"id"`
	ApplicationID string              `json:"applicationId"`
	VersionID     string              `json:"versionId"`
	BundleVersion string              `json:"bundleVersion"`
	PlatformTag   string              `json:"platformTag"`
	Forced        bool                `json:"forced"`
	MinAppVersion string              `json:"minAppVersion"`
	Status        string              `json:"status"`
	Upload        *OtaUploadGrantView `json:"upload"`
}

// OtaBundleView is the bundle read (GET /api/v1/ota/bundles/{id}): the
// landed manifest facts (sha256, size, platformTag) beside the
// Ed25519 signature and its key id (D8) and the V55 status vocabulary
// (UPLOADING, VALIDATING, SIGNED, RELEASED, REVOKED).
type OtaBundleView struct {
	ID            string `json:"id"`
	ApplicationID string `json:"applicationId"`
	VersionID     string `json:"versionId"`
	ArtifactID    string `json:"artifactId"`
	ReleaseID     string `json:"releaseId"`
	BundleVersion string `json:"bundleVersion"`
	PlatformTag   string `json:"platformTag"`
	Sha256        string `json:"sha256"`
	SizeBytes     int64  `json:"sizeBytes"`
	Signature     string `json:"signature"`
	KeyID         string `json:"keyId"`
	Forced        bool   `json:"forced"`
	MinAppVersion string `json:"minAppVersion"`
	Status        string `json:"status"`
	CreatedBy     string `json:"createdBy"`
	CreatedAt     string `json:"createdAt"`
	UpdatedAt     string `json:"updatedAt"`
}

// OtaBundlePage is the bundle listing's page shape.
type OtaBundlePage struct {
	Items      []OtaBundleView `json:"items"`
	NextCursor string          `json:"next_cursor"`
}

// OtaFinalizeView answers POST /api/v1/ota/bundles/{id}/finalize;
// created false means the retry replayed the already-SIGNED bundle.
type OtaFinalizeView struct {
	Created bool           `json:"created"`
	Bundle  *OtaBundleView `json:"bundle"`
}

// IssueOtaAppTokenRequest is POST /api/v1/ota/app-tokens: the
// permission-target application and the human name the registry
// distinguishes tokens by.
type IssueOtaAppTokenRequest struct {
	ApplicationID string `json:"applicationId"`
	Name          string `json:"name"`
}

// OtaAppTokenView is the registry row - it never carries the token
// value (the server stores only its SHA-256).
type OtaAppTokenView struct {
	ID            string `json:"id"`
	ApplicationID string `json:"applicationId"`
	Name          string `json:"name"`
	CreatedBy     string `json:"createdBy"`
	Revoked       bool   `json:"revoked"`
	RevokedAt     string `json:"revokedAt"`
}

// OtaIssuedView is the issue answer: the registry row beside the
// sdc1. value - the only time the value ever leaves the server.
type OtaIssuedView struct {
	Token *OtaAppTokenView `json:"token"`
	Value string           `json:"value"`
}

// OtaStageBody is one ladder stage of the attach body: the cumulative
// basisPoints threshold (1..10000) and the optional operator note.
type OtaStageBody struct {
	BasisPoints int32  `json:"basisPoints"`
	Note        string `json:"note,omitempty"`
}

// AttachOtaRolloutRequest is POST /api/v1/ota/rollouts: the owning
// application, the release the plan hangs on, the advancement mode
// (AUTOMATIC or CHECKPOINT), and the strictly ascending cumulative
// basis-point ladder whose final stage is 10000.
type AttachOtaRolloutRequest struct {
	ApplicationID string         `json:"applicationId"`
	ReleaseID     string         `json:"releaseId"`
	Mode          string         `json:"mode"`
	Stages        []OtaStageBody `json:"stages"`
}

// OtaControlRequest is the halt/resume body: the permission-target
// application and the action's reason (mandatory for halt).
type OtaControlRequest struct {
	ApplicationID string `json:"applicationId"`
	Reason        string `json:"reason,omitempty"`
}

// OtaRolloutStageView is one stage of the plan's ladder: its position,
// cumulative threshold, and standing (REACHED or PENDING) with the
// reach moment.
type OtaRolloutStageView struct {
	Position    int32  `json:"position"`
	BasisPoints int32  `json:"basisPoints"`
	Note        string `json:"note"`
	Status      string `json:"status"`
	ReachedAt   string `json:"reachedAt"`
}

// OtaRolloutEventView is one row of the plan's append-only timeline.
type OtaRolloutEventView struct {
	Action        string `json:"action"`
	StagePosition int32  `json:"stagePosition"`
	Detail        string `json:"detail"`
	ActorID       string `json:"actorId"`
	OccurredAt    string `json:"occurredAt"`
}

// OtaRolloutView is the rollout status read: the plan's mode and
// standing (RUNNING, HALTED, PAUSED, COMPLETE), the full ladder, and
// the timeline.
type OtaRolloutView struct {
	ReleaseID     string                `json:"releaseId"`
	ApplicationID string                `json:"applicationId"`
	Mode          string                `json:"mode"`
	Status        string                `json:"status"`
	CurrentStage  int32                 `json:"currentStage"`
	PlanSalt      string                `json:"planSalt"`
	Stages        []OtaRolloutStageView `json:"stages"`
	Timeline      []OtaRolloutEventView `json:"timeline"`
}

// OtaFunnelStagesView is the funnel's stage counters (D9: checks are
// counted server-side; the lifecycle events ride the sdt1. ingest).
type OtaFunnelStagesView struct {
	Checks     int64 `json:"checks"`
	Seen       int64 `json:"seen"`
	Downloads  int64 `json:"downloads"`
	Applied    int64 `json:"applied"`
	Failed     int64 `json:"failed"`
	RolledBack int64 `json:"rolledBack"`
}

// OtaFailureView is one failure reason and its count.
type OtaFailureView struct {
	Reason string `json:"reason"`
	Count  int64  `json:"count"`
}

// OtaAdoptionPointView is one day of the adoption curve.
type OtaAdoptionPointView struct {
	Day            string  `json:"day"`
	SeenDevices    int64   `json:"seenDevices"`
	AppliedDevices int64   `json:"appliedDevices"`
	AdoptionRate   float64 `json:"adoptionRate"`
}

// OtaFunnelView is the dashboard funnel read (GET
// /api/v1/tenants/{tenantId}/dashboard/ota/bundles/{bundleId}/funnel):
// the bundle's identity, the check -> seen -> download -> applied
// stage counters, the failure-reason breakdown, and the adoption
// curve over time.
type OtaFunnelView struct {
	BundleID      string                 `json:"bundleId"`
	BundleVersion string                 `json:"bundleVersion"`
	ApplicationID string                 `json:"applicationId"`
	ReleaseID     string                 `json:"releaseId"`
	ReleaseRef    string                 `json:"releaseRef"`
	Stages        *OtaFunnelStagesView   `json:"stages"`
	Failures      []OtaFailureView       `json:"failures"`
	Adoption      []OtaAdoptionPointView `json:"adoption"`
}
