package api

import (
	"net/url"
	"strings"
)

// Every API path the CLI calls, in one file. These templates carry the
// baseline contract's own placeholder names and are verified against
// docs/openapi/baseline.yaml by endpoints_test.go - the CLI never
// hand-rolls a path that the served contract does not declare.
//
// The story's technical note ("reuse generated OpenAPI client; never
// hand-roll endpoint URLs") is honored structurally: there is exactly
// one place a URL is composed, and a test pins it to the spec (the
// generated-client alternative is weighed in ADR-0004).
const (
	pathAuthMe = "/api/v1/auth/me"

	pathArtifacts             = "/api/v1/artifacts"
	pathArtifact              = "/api/v1/artifacts/{artifactId}"
	pathArtifactFinalize      = "/api/v1/artifacts/{artifactId}/finalize"
	pathArtifactSessions      = "/api/v1/artifacts/upload-sessions"
	pathArtifactRevalidate    = "/api/v1/artifacts/{artifactId}/revalidate"
	pathApplications          = "/api/v1/applications"
	pathApplication           = "/api/v1/applications/{id}"
	pathApplicationVersions   = "/api/v1/applications/{applicationId}/versions"
	pathApplicationVersionOne = "/api/v1/applications/{applicationId}/versions/{versionId}"

	pathReleases            = "/api/v1/releases"
	pathReleaseOne          = "/api/v1/releases/{releaseId}"
	pathReleaseTransitions  = "/api/v1/releases/{releaseId}/transitions"
	pathReleaseManifest     = "/api/v1/releases/{releaseId}/manifest"
	pathReleaseGates        = "/api/v1/releases/{releaseId}/gates"
	pathReleaseGateResult   = "/api/v1/releases/{releaseId}/gates/{gateType}/result"
	pathReleaseGateOverride = "/api/v1/releases/{releaseId}/gates/{gateType}/override"
	pathReleasePromote      = "/api/v1/releases/{releaseId}/promote"
	pathReleasePromotionPre = "/api/v1/releases/{releaseId}/promotion-precheck"
	pathReleasePromotions   = "/api/v1/releases/{releaseId}/promotions"

	pathOtaBundles        = "/api/v1/ota/bundles"
	pathOtaBundleOne      = "/api/v1/ota/bundles/{bundleId}"
	pathOtaBundleFinalize = "/api/v1/ota/bundles/{bundleId}/finalize"
	pathOtaAppTokens      = "/api/v1/ota/app-tokens"
	pathOtaRollouts       = "/api/v1/ota/rollouts"
	pathOtaRolloutOne     = "/api/v1/ota/rollouts/{releaseId}"
	pathOtaRolloutHalt    = "/api/v1/ota/rollouts/{releaseId}/halt"
	pathOtaRolloutResume  = "/api/v1/ota/rollouts/{releaseId}/resume"
	pathOtaFunnel         = "/api/v1/tenants/{tenantId}/dashboard/ota/bundles/{bundleId}/funnel"
)

// endpointTemplates lists every template above; endpoints_test.go
// asserts each one is a path declared in the OpenAPI baseline.
var endpointTemplates = []string{
	pathAuthMe,
	pathArtifacts,
	pathArtifact,
	pathArtifactFinalize,
	pathArtifactSessions,
	pathArtifactRevalidate,
	pathApplications,
	pathApplication,
	pathApplicationVersions,
	pathApplicationVersionOne,
	pathReleases,
	pathReleaseOne,
	pathReleaseTransitions,
	pathReleaseManifest,
	pathReleaseGates,
	pathReleaseGateResult,
	pathReleaseGateOverride,
	pathReleasePromote,
	pathReleasePromotionPre,
	pathReleasePromotions,
	pathOtaBundles,
	pathOtaBundleOne,
	pathOtaBundleFinalize,
	pathOtaAppTokens,
	pathOtaRollouts,
	pathOtaRolloutOne,
	pathOtaRolloutHalt,
	pathOtaRolloutResume,
	pathOtaFunnel,
}

// expand replaces each {placeholder} in order with the given segment
// values, escaping them for a URL path.
func expand(template string, values ...string) string {
	out := template
	for _, value := range values {
		out = replaceFirstPlaceholder(out, value)
	}
	return out
}

// replaceFirstPlaceholder swaps the first {...} segment for value,
// URL-escaped for a path slot.
func replaceFirstPlaceholder(template, value string) string {
	open := strings.IndexByte(template, '{')
	if open < 0 {
		return template
	}
	close := strings.IndexByte(template[open:], '}')
	if close < 0 {
		return template
	}
	return template[:open] + url.PathEscape(value) + template[open+close+1:]
}
