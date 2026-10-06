package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

// maxPages bounds page-walking so a listing can never loop forever on
// a server that keeps handing out cursors.
const maxPages = 10

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// LooksLikeUUID reports whether ref is already an id (no resolution
// needed).
func LooksLikeUUID(ref string) bool {
	return uuidPattern.MatchString(ref)
}

// ResolutionError marks a name reference that could not be resolved to
// exactly one target - a usage-class failure (exit 2), never an API
// error.
type ResolutionError struct {
	Ref        string
	Kind       string // "application" or "version"
	Candidates []string
	Reason     string
}

func (e *ResolutionError) Error() string {
	if e.Reason != "" {
		return fmt.Sprintf("resolving %s %q: %s", e.Kind, e.Ref, e.Reason)
	}
	if len(e.Candidates) == 0 {
		return fmt.Sprintf("no %s named %q is visible to your account", e.Kind, e.Ref)
	}
	return fmt.Sprintf("%s name %q is ambiguous across %s", e.Kind, e.Ref, strings.Join(e.Candidates, ", "))
}

// Me returns the authenticated actor.
func (c *Client) Me(ctx context.Context) (*MeResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	var me MeResponse
	if err := c.callJSON(ctx, http.MethodGet, pathAuthMe, nil, nil, nil, &me, "authenticate"); err != nil {
		return nil, err
	}
	return &me, nil
}

// GetApplication reads one application by id.
func (c *Client) GetApplication(ctx context.Context, id string) (*ApplicationView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	var view ApplicationView
	if err := c.callJSON(ctx, http.MethodGet, pathApplication, []string{id}, nil, nil, &view, "read application"); err != nil {
		return nil, err
	}
	return &view, nil
}

// ListApplications walks the organization-wide application listing
// (the caller's own context decides what is visible).
func (c *Client) ListApplications(ctx context.Context, organizationID string) ([]ApplicationView, error) {
	var all []ApplicationView
	cursor := ""
	for page := 0; page < maxPages; page++ {
		q := url.Values{"organizationId": {organizationID}, "limit": {"100"}}
		if cursor != "" {
			q.Set("cursor", cursor)
		}
		ctx, cancel := context.WithTimeout(ctx, shortTimeout)
		var pageView ApplicationPage
		err := c.callJSON(ctx, http.MethodGet, pathApplications, nil, q, nil, &pageView, "list applications")
		cancel()
		if err != nil {
			return nil, err
		}
		all = append(all, pageView.Items...)
		if pageView.NextCursor == "" {
			break
		}
		cursor = pageView.NextCursor
	}
	return all, nil
}

// ListVersions walks an application's version listing. tag and include
// pass through to the server's filters (validated server-side).
func (c *Client) ListVersions(ctx context.Context, applicationID, tag, include string) ([]VersionView, error) {
	var all []VersionView
	cursor := ""
	for page := 0; page < maxPages; page++ {
		q := url.Values{"limit": {"100"}}
		if tag != "" {
			q.Set("tag", tag)
		}
		if include != "" {
			q.Set("include", include)
		}
		if cursor != "" {
			q.Set("cursor", cursor)
		}
		ctx, cancel := context.WithTimeout(ctx, shortTimeout)
		var pageView VersionPage
		err := c.callJSON(ctx, http.MethodGet, pathApplicationVersions, []string{applicationID}, q, nil, &pageView, "list versions")
		cancel()
		if err != nil {
			return nil, err
		}
		all = append(all, pageView.Items...)
		if pageView.NextCursor == "" {
			break
		}
		cursor = pageView.NextCursor
	}
	return all, nil
}

// GetVersion reads one version by id.
func (c *Client) GetVersion(ctx context.Context, applicationID, versionID string) (*VersionView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	var view VersionView
	if err := c.callJSON(ctx, http.MethodGet, pathApplicationVersionOne, []string{applicationID, versionID}, nil, nil, &view, "read version"); err != nil {
		return nil, err
	}
	return &view, nil
}

// ResolveApplication turns an app name-or-uuid into the registry row.
// Name resolution runs through the caller's organization listing, so
// visibility follows the caller's tenant context.
func (c *Client) ResolveApplication(ctx context.Context, ref string) (*ApplicationView, error) {
	if LooksLikeUUID(ref) {
		return c.GetApplication(ctx, ref)
	}
	orgID, err := c.requireOrgID(ctx)
	if err != nil {
		return nil, err
	}
	apps, err := c.ListApplications(ctx, orgID)
	if err != nil {
		return nil, err
	}
	var matches []ApplicationView
	for i := range apps {
		if strings.EqualFold(apps[i].Name, ref) {
			matches = append(matches, apps[i])
		}
	}
	switch len(matches) {
	case 1:
		return &matches[0], nil
	case 0:
		return nil, &ResolutionError{Kind: "application", Ref: ref}
	default:
		names := make([]string, len(matches))
		for i := range matches {
			names[i] = matches[i].Name + " (" + matches[i].ID + ")"
		}
		return nil, &ResolutionError{Kind: "application", Ref: ref, Candidates: names}
	}
}

// ResolveVersion turns a version name-or-uuid (within one application)
// into the version row. The listing is newest-first; exact name match
// decides.
func (c *Client) ResolveVersion(ctx context.Context, applicationID, ref string) (*VersionView, error) {
	if LooksLikeUUID(ref) {
		return c.GetVersion(ctx, applicationID, ref)
	}
	versions, err := c.ListVersions(ctx, applicationID, "", "archived,revoked")
	if err != nil {
		return nil, err
	}
	for i := range versions {
		if versions[i].VersionName == ref {
			return &versions[i], nil
		}
	}
	return nil, &ResolutionError{Kind: "version", Ref: ref, Reason: "not found in this application"}
}

// ResolveVersionAnywhere finds a version by name across every
// application visible to the caller - the `artifact upload
// --version 7.12.0` ergonomics of the issue's example, where no
// application is named. Exactly one hit is required; several hits
// return the candidate list and ask for --app.
func (c *Client) ResolveVersionAnywhere(ctx context.Context, ref string) (*ApplicationView, *VersionView, error) {
	if LooksLikeUUID(ref) {
		return nil, nil, &ResolutionError{Kind: "version", Ref: ref,
			Reason: "a version id still needs --app (the artifact surface is application-addressed)"}
	}
	orgID, err := c.requireOrgID(ctx)
	if err != nil {
		return nil, nil, err
	}
	apps, err := c.ListApplications(ctx, orgID)
	if err != nil {
		return nil, nil, err
	}
	var foundApps []string
	var found *VersionView
	var foundApp *ApplicationView
	for i := range apps {
		versions, err := c.ListVersions(ctx, apps[i].ID, "", "archived,revoked")
		if err != nil {
			var apiErr *APIError
			if errors.As(err, &apiErr) && !apiErr.Network && apiErr.Problem.Status == http.StatusNotFound {
				continue
			}
			return nil, nil, err
		}
		for j := range versions {
			if versions[j].VersionName == ref {
				foundApps = append(foundApps, apps[i].Name+" ("+apps[i].ID+")")
				found = &versions[j]
				foundApp = &apps[i]
			}
		}
	}
	switch len(foundApps) {
	case 1:
		return foundApp, found, nil
	case 0:
		return nil, nil, &ResolutionError{Kind: "version", Ref: ref}
	default:
		return nil, nil, &ResolutionError{Kind: "version", Ref: ref, Candidates: foundApps}
	}
}

// requireOrgID fetches /me and demands an organization context for
// name resolution (org-less callers must use ids).
func (c *Client) requireOrgID(ctx context.Context) (string, error) {
	me, err := c.Me(ctx)
	if err != nil {
		return "", err
	}
	if me.OrgID == "" {
		return "", &ResolutionError{Kind: "name", Ref: "", Reason: "your account has no active organization context; pass ids instead of names"}
	}
	return me.OrgID, nil
}
