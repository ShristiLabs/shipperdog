package com.shipperdog.companion.core

/**
 * The tester-facing API client of the companion core (issue #73,
 * S15-02): the SAME routes the tester portal's own client calls
 * (portal/src/api), a third client of the frozen contracts — no
 * companion-specific endpoint exists or is needed:
 *
 * - session: `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`
 *   (the portal session machinery IS the companion's auth — the spike
 *   substrate decision, docs/companion-spike.md: the sdt1 telemetry
 *   token is ingest-only and a new device-token path would be an
 *   invented parallel authority);
 * - catalog: `GET /api/v1/authorization/decisions/applications`
 *   (the S7 decision path, server-computed — never a cached list);
 * - history: `GET /api/v1/authorization/decisions/eligibility`
 *   (the installable ANDROID artifact a download decision would
 *   authorize — the catalog card deliberately does NOT carry the
 *   artifact id, the eligibility listing does);
 * - issuance: `GET /api/v1/artifacts/{artifactId}/download
 *   ?version_id=&tenant_id=` (the engine's DOWNLOAD decision is the
 *   route's entire authority, S8-04).
 */
class PortalClient internal constructor(
    private val config: CompanionConfig,
    private val http: CompanionHttp,
) {

    /** Logs the tester in; answers the session pair the portal itself would hold. */
    fun login(email: String, password: String): TokenPair {
        val body = Json.write(mapOf("email" to email, "password" to password))
        val response = http.exchange(
            CompanionHttp.Request("POST", config.url("/api/v1/auth/login"), jsonBody = body),
        )
        if (response.status != 200) {
            throw PortalProblem.from("login", response)
        }
        return PortalModelsJson.tokenPair(response.bodyText())
    }

    /** Refreshes the session; a non-200 means the tester logs in again. */
    fun refresh(refreshToken: String): TokenPair {
        val body = Json.write(mapOf("refreshToken" to refreshToken))
        val response = http.exchange(
            CompanionHttp.Request("POST", config.url("/api/v1/auth/refresh"), jsonBody = body),
        )
        if (response.status != 200) {
            throw PortalProblem.from("refresh", response)
        }
        return PortalModelsJson.tokenPair(response.bodyText())
    }

    /** One cursor page of the caller's authorized applications. */
    fun catalog(cursor: String? = null, bearer: String): CatalogPage {
        val path = buildString {
            append("/api/v1/authorization/decisions/applications?limit=")
            append(config.pageSize)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(java.net.URLEncoder.encode(cursor, Charsets.UTF_8))
            }
        }
        val response = http.exchange(
            CompanionHttp.Request("GET", config.url(path), bearerToken = bearer),
        )
        if (response.status != 200) {
            throw PortalProblem.from("catalog", response)
        }
        return PortalModelsJson.catalogPage(response.bodyText())
    }

    /** The authorized version history of one application (the ANDROID slots carry the artifact ids). */
    fun history(tenantId: String, applicationId: String, bearer: String): List<HistoryEntry> {
        val path = "/api/v1/authorization/decisions/eligibility?tenantId=" + tenantId +
            "&applicationId=" + applicationId + "&limit=" + config.pageSize
        val response = http.exchange(
            CompanionHttp.Request("GET", config.url(path), bearerToken = bearer),
        )
        if (response.status != 200) {
            throw PortalProblem.from("history", response)
        }
        return PortalModelsJson.historyEntries(response.bodyText())
    }

    /** One download issuance for the artifact the history slot named (the engine's decision is the authority). */
    fun issuance(artifactId: String, versionId: String, tenantId: String, bearer: String): DownloadIssuance {
        val path = "/api/v1/artifacts/" + artifactId + "/download?version_id=" + versionId +
            "&tenant_id=" + tenantId
        val response = http.exchange(
            CompanionHttp.Request("GET", config.url(path), bearerToken = bearer),
        )
        if (response.status != 200) {
            throw PortalProblem.from("issuance", response)
        }
        return PortalModelsJson.downloadIssuance(response.bodyText())
    }

    /**
     * The issuance URL made absolute: emulator deployments answer an
     * API-relative path (`/api/v1/downloads/{token}` — the nginx
     * proxy's same-origin shape) while production answers an absolute
     * GCS URL; the companion resolves relative answers against its
     * configured base exactly like the portal's own hosting does.
     */
    fun absoluteIssuanceUrl(issuance: DownloadIssuance): String =
        if (issuance.url.startsWith("http://") || issuance.url.startsWith("https://")) {
            issuance.url
        } else {
            config.baseUrl.trimEnd('/') + issuance.url
        }

    companion object {
        /** The production client (the ShipperDog.start doctrine: seams stay internal, the factory is the entry). */
        fun production(config: CompanionConfig): PortalClient = PortalClient(config, UrlConnectionHttp())
    }
}

/**
 * The API problem the portal's problem envelope answers with — the
 * stable code (or the HTTP status when the body is not the envelope)
 * the companion surfaces verbatim: denial concealment means a 404 is
 * opaque by design (§12/INV-005), the companion does not guess why.
 */
class PortalProblem private constructor(val surface: String, val code: String) :
    RuntimeException("$surface refused: $code") {

    companion object {
        internal fun from(surface: String, response: CompanionHttp.Response): PortalProblem {
            val code = try {
                (Json.parse(response.bodyText()) as? Map<String, Any?>)?.get("code") as? String
            } catch (malformed: Json.JsonException) {
                null
            }
            return PortalProblem(surface, code ?: ("HTTP_" + response.status))
        }
    }
}

/** The companion's configured base (scheme + host, no path) and page size. */
data class CompanionConfig(
    val baseUrl: String,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "baseUrl must be absolute (scheme + host)"
        }
        require(pageSize > 0) { "pageSize must be positive" }
    }

    /** One API path joined to the base. */
    fun url(path: String): String = baseUrl.trimEnd('/') + path

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 20
    }
}
