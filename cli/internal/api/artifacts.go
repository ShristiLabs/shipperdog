package api

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"time"
)

// DefaultMultipartLimit mirrors ARTIFACT_RESUMABLE_THRESHOLD_BYTES
// (100 MiB): the server refuses multipart above its threshold, so the
// CLI routes larger files to the session path before wasting a request.
const DefaultMultipartLimit int64 = 104857600

// UploadOptions carries the client-asserted upload claims. The five
// provenance members are the frozen S5-04 contract.
type UploadOptions struct {
	VersionID      string
	Platform       string // IOS or ANDROID
	BuildNumber    *int64
	GitCommit      string
	GitBranch      string
	Pipeline       string
	CiRunUrl       string
	BuildTimestamp string
}

// query renders the shared claim set as the multipart route's query
// parameters (the shape declared in the OpenAPI baseline).
func (o UploadOptions) query() url.Values {
	q := url.Values{}
	q.Set("version_id", o.VersionID)
	q.Set("platform", o.Platform)
	if o.BuildNumber != nil {
		q.Set("build_number", fmt.Sprintf("%d", *o.BuildNumber))
	}
	setIfPresent(q, "git_commit", o.GitCommit)
	setIfPresent(q, "git_branch", o.GitBranch)
	setIfPresent(q, "pipeline", o.Pipeline)
	setIfPresent(q, "ci_run_url", o.CiRunUrl)
	setIfPresent(q, "build_timestamp", o.BuildTimestamp)
	return q
}

func setIfPresent(q url.Values, key, value string) {
	if value != "" {
		q.Set(key, value)
	}
}

// progressReader counts bytes streamed and reports to the callback.
type progressReader struct {
	r        io.Reader
	sent     int64
	reporter func(sent, total int64)
	total    int64
}

func (p *progressReader) Read(buf []byte) (int, error) {
	n, err := p.r.Read(buf)
	p.sent += int64(n)
	if p.reporter != nil && n > 0 {
		p.reporter(p.sent, p.total)
	}
	return n, err
}

// UploadMultipart streams the file through POST /api/v1/artifacts (the
// route for binaries at or below the resumable threshold). The body
// streams from disk; nothing is buffered whole.
func (c *Client) UploadMultipart(ctx context.Context, opts UploadOptions, filePath string, reporter func(sent, total int64)) (*ArtifactView, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, fmt.Errorf("opening %s: %w", filePath, err)
	}
	defer file.Close()

	pr, pw := io.Pipe()
	writer := multipart.NewWriter(pw)
	go func() {
		part, partErr := writer.CreateFormFile("file", filepath.Base(filePath))
		if partErr != nil {
			_ = pw.CloseWithError(partErr)
			return
		}
		if stat, statErr := file.Stat(); statErr == nil {
			_, _ = io.Copy(part, &progressReader{r: file, total: stat.Size(), reporter: reporter})
		} else {
			_, _ = io.Copy(part, file)
		}
		_ = writer.Close()
		_ = pw.Close()
	}()

	u := c.BaseURL + expand(pathArtifacts) + "?" + opts.query().Encode()
	req, reqErr := http.NewRequestWithContext(ctx, http.MethodPost, u, pr)
	if reqErr != nil {
		return nil, fmt.Errorf("building upload request: %w", reqErr)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	req.Header.Set("X-Correlation-Id", newCorrelationID())
	req.Header.Set("Idempotency-Key", idempotencyKey())
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}

	view, _, err := c.doForArtifactView(req, "artifact upload")
	return view, err
}

// doForArtifactView executes an upload-shaped request and decodes the
// artifact view answer.
func (c *Client) doForArtifactView(req *http.Request, operation string) (*ArtifactView, http.Header, error) {
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, nil, &APIError{Operation: operation, Inner: err, Network: true}
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return nil, resp.Header, &APIError{Operation: operation, Inner: err, Network: true}
	}
	if resp.StatusCode >= 400 {
		apiErr := &APIError{Operation: operation, Problem: Problem{Status: resp.StatusCode}}
		_ = parseJSON(data, &apiErr.Problem)
		return nil, resp.Header, apiErr
	}
	var view ArtifactView
	if err := parseJSON(data, &view); err != nil {
		return nil, resp.Header, fmt.Errorf("%s: decoding response: %w", operation, err)
	}
	return &view, resp.Header, nil
}

// CreateUploadSession opens the direct-to-storage session for large
// binaries (>100 MB rule), carrying the provenance claims at creation
// time per the S5-04 capture-point rule.
func (c *Client) CreateUploadSession(ctx context.Context, reqBody CreateSessionRequest) (*UploadSessionView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	var view UploadSessionView
	if err := c.callJSON(ctx, http.MethodPost, pathArtifactSessions, nil, nil, reqBody, &view, "create upload session"); err != nil {
		return nil, err
	}
	return &view, nil
}

// PutToStorage streams the bytes direct to the granted session URL.
// The URL is storage-side (presigned/emulator): it is requested as-is,
// without the API bearer header - embedding auth there is the server's
// job, not this client's. Length is declared so storage rejects
// truncation rather than storing it silently.
func (c *Client) PutToStorage(ctx context.Context, uploadURL, filePath string, size int64, reporter func(sent, total int64)) error {
	file, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("opening %s: %w", filePath, err)
	}
	defer file.Close()

	req, reqErr := http.NewRequestWithContext(ctx, http.MethodPut, uploadURL, &progressReader{r: file, total: size, reporter: reporter})
	if reqErr != nil {
		return fmt.Errorf("building storage request: %w", reqErr)
	}
	req.ContentLength = size
	req.Header.Set("Content-Type", "application/octet-stream")

	// The upload URL may point at a different host (GCS emulator with
	// -external-url, or production GCS); it is fetched with its own
	// client, deliberately not sharing API defaults.
	storageClient := &http.Client{Transport: &http.Transport{
		TLSClientConfig: &tls.Config{},
	}}
	resp, err := storageClient.Do(req)
	if err != nil {
		return &APIError{Operation: "upload bytes to storage", Inner: err, Network: true}
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 400 {
		return &APIError{Operation: "upload bytes to storage", Problem: Problem{Status: resp.StatusCode}}
	}
	return nil
}

// Finalize registers the artifact after direct bytes landed.
func (c *Client) Finalize(ctx context.Context, artifactID, versionID string) (*FinalizeView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	var view FinalizeView
	body := map[string]string{"versionId": versionID}
	if err := c.callJSON(ctx, http.MethodPost, pathArtifactFinalize, []string{artifactID}, nil, body, &view, "finalize upload"); err != nil {
		return nil, err
	}
	return &view, nil
}

// GetArtifact reads the artifact view - where the extraction and
// validation tiers settle.
func (c *Client) GetArtifact(ctx context.Context, artifactID, versionID string) (*ArtifactView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	q := url.Values{"version_id": {versionID}}
	var view ArtifactView
	if err := c.callJSON(ctx, http.MethodGet, pathArtifact, []string{artifactID}, q, nil, &view, "read artifact"); err != nil {
		return nil, err
	}
	return &view, nil
}

// Revalidate drives the validation retry entry (202 async; the read is
// polled afterwards to settle).
func (c *Client) Revalidate(ctx context.Context, artifactID, versionID string) (*ArtifactView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	q := url.Values{"version_id": {versionID}}
	var view ArtifactView
	if err := c.callJSON(ctx, http.MethodPost, pathArtifactRevalidate, []string{artifactID}, q, nil, &view, "revalidate artifact"); err != nil {
		return nil, err
	}
	return &view, nil
}

// Settled reports whether the artifact status has left the async
// pipeline's in-flight states (VALIDATING, and the pre-registration
// UPLOADING) - i.e. READY or FAILED.
func Settled(v *ArtifactView) bool {
	switch v.Status {
	case "VALIDATING", "UPLOADING", "":
		return false
	default:
		return true
	}
}

// WaitForSettle polls the artifact read until the validation tier
// settles or the deadline passes. Returns the last view read.
func (c *Client) WaitForSettle(ctx context.Context, artifactID, versionID string, interval time.Duration, deadline time.Time, note func(*ArtifactView)) (*ArtifactView, error) {
	var last *ArtifactView
	for {
		view, err := c.GetArtifact(ctx, artifactID, versionID)
		if err != nil {
			return last, err
		}
		last = view
		if Settled(view) {
			return view, nil
		}
		if note != nil {
			note(view)
		}
		if time.Now().After(deadline) {
			return last, nil
		}
		select {
		case <-ctx.Done():
			return last, ctx.Err()
		case <-time.After(interval):
		}
	}
}

func parseJSON(data []byte, out any) error {
	return json.Unmarshal(data, out)
}
