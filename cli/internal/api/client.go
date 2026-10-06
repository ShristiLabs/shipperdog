// Package api is the CLI's thin HTTP client over the served /api/v1
// contract (docs/openapi/baseline.yaml). It owns: bearer auth, the
// X-Correlation-Id propagation the platform's problem+json envelope
// joins on, error classification into the CLI's exit-code classes, and
// the two upload routes (multipart at/below the threshold, direct-to-
// storage session above it - mirroring the server's own rule).
package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// Client talks to one ShipperDog API base URL with one credential.
type Client struct {
	BaseURL string
	Token   string
	HTTP    *http.Client
}

// New builds a client. baseURL carries no trailing slash.
func New(baseURL string, token string) *Client {
	return &Client{
		BaseURL: baseURL,
		Token:   token,
		HTTP:    &http.Client{},
	}
}

// APIError is a non-2xx problem+json response, classified for the CLI's
// exit codes: 401 -> auth, other 4xx -> validation, 5xx -> server.
type APIError struct {
	Problem   Problem
	Network   bool
	Inner     error
	Operation string
}

func (e *APIError) Error() string {
	if e.Network {
		return fmt.Sprintf("%s: network error: %v", e.Operation, e.Inner)
	}
	if e.Problem.Code != "" {
		msg := fmt.Sprintf("%s: %s (HTTP %d)", e.Operation, e.Problem.Code, e.Problem.Status)
		if e.Problem.Detail != "" {
			msg += ": " + e.Problem.Detail
		}
		return msg
	}
	return fmt.Sprintf("%s: HTTP %d", e.Operation, e.Problem.Status)
}

// TraceID returns the response correlation id when the server supplied
// one - printed to stderr so CI logs can join with server-side traces.
func (e *APIError) TraceID() string {
	if e.Problem.TraceID != "" {
		return e.Problem.TraceID
	}
	return ""
}

// newCorrelationID returns a random RFC 4122 v4 UUID string.
func newCorrelationID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("anon-%d", time.Now().UnixNano())
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

// request performs one API call. pathTemplate is one of the constants
// in endpoints.go; query may be nil; body may be nil.
func (c *Client) request(ctx context.Context, method, pathTemplate string, args []string, query url.Values, contentType string, body io.Reader, operation string) ([]byte, http.Header, error) {
	u := c.BaseURL + expand(pathTemplate, args...)
	if len(query) > 0 {
		u += "?" + query.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return nil, nil, &APIError{Operation: operation, Inner: err, Network: false, Problem: Problem{Status: 0}}
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("X-Correlation-Id", newCorrelationID())
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
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
		_ = json.Unmarshal(data, &apiErr.Problem)
		return data, resp.Header, apiErr
	}
	return data, resp.Header, nil
}

// callJSON performs a request whose body (if any) is JSON and whose
// response is decoded into out.
func (c *Client) callJSON(ctx context.Context, method, pathTemplate string, args []string, query url.Values, in any, out any, operation string) error {
	var body io.Reader
	contentType := ""
	if in != nil {
		encoded, err := json.Marshal(in)
		if err != nil {
			return fmt.Errorf("%s: encoding request: %w", operation, err)
		}
		body = bytes.NewReader(encoded)
		contentType = "application/json"
	}
	data, _, err := c.request(ctx, method, pathTemplate, args, query, contentType, body, operation)
	if err != nil {
		return err
	}
	if out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			return fmt.Errorf("%s: decoding response: %w", operation, err)
		}
	}
	return nil
}

// idempotencyKey returns a fresh UUID-shaped key for designated POSTs.
func idempotencyKey() string {
	return newCorrelationID()
}

// shortTimeout bounds ordinary API calls (not uploads, not polls).
const shortTimeout = 30 * time.Second
