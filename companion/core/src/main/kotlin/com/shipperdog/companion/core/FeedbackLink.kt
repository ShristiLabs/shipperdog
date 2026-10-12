package com.shipperdog.companion.core

/**
 * The companion's feedback handoff (issue #73, S15-02, green-lit
 * AC's feedback deep link): the tester portal's version deep link
 * with the report trigger — the SAME `?report=1` contract the
 * telemetry SDK's `feedbackUrl` opens and the portal's version page
 * honors (S11-01: the dialog opens with the tester editing and
 * submitting; nothing posts itself). The companion links the VERSION
 * page (its ledger knows the version it installed) — the SDK links
 * the application page because its config carries no version id; the
 * contracts are identical from the portal's side.
 */
object FeedbackLink {

    /** The report deep link for one installed (or offered) version of an application. */
    fun forVersion(
        portalBaseUrl: String,
        tenantId: String,
        applicationId: String,
        versionId: String,
        hint: String? = null,
    ): String {
        val base = portalBaseUrl.trimEnd('/') +
            "/applications/" + tenantId + "/" + applicationId + "/versions/" + versionId + "?report=1"
        if (hint.isNullOrBlank()) {
            return base
        }
        // The SDK's cap doctrine: a URL parameter, not a log shipper.
        val capped = hint.take(MAX_HINT_CHARS).replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return base + "&hint=" + java.net.URLEncoder.encode(capped, Charsets.UTF_8)
    }

    /** The application page's report link (the untracked fallback — no version to address). */
    fun forApplication(portalBaseUrl: String, tenantId: String, applicationId: String): String =
        portalBaseUrl.trimEnd('/') + "/applications/" + tenantId + "/" + applicationId + "?report=1"

    /** The SDK's own prefill cap (docs/telemetry-sdk.md): a deep link is a URL, not a log shipper. */
    const val MAX_HINT_CHARS: Int = 2_000
}
