package api

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
