package com.shipperdog.companion.core

/**
 * The companion's model of the tester-facing API surface (issue #73,
 * S15-02): the portal session, the authorized catalog, the version
 * history (eligibility), and the download issuance — the frozen
 * shapes of docs/openapi/baseline.yaml exactly as the portal's own
 * TypeScript clients consume them (portal/src/api), so the companion
 * is a THIRD client of the same contracts, never a parallel surface.
 *
 * Parsing is fail-soft on unknown members (the Json reader answers
 * plain structures; these parsers pick what they know) and fail-hard
 * on absent required members — a malformed answer throws
 * [MalformedAnswer] rather than rendering a half-true catalog.
 */
internal class MalformedAnswer(message: String) : RuntimeException(message)

/** The session the portal's login route answers (the companion's whole credential set). */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/** One authorized application card of the catalog (S8-02). */
data class CatalogCard(
    val tenantId: String,
    val applicationId: String,
    val applicationName: String,
    val latestVersionId: String?,
    val latestVersionName: String?,
    val androidVersionId: String?,
    val androidVersionName: String?,
    /**
     * True when the ANDROID resolution's version is an OTA bundle's
     * own versions row (S16-16): the platform's newest authorized
     * build ships over the air to the embedded updater — the
     * companion can neither install it nor compare its label against
     * the installed store build. Absent on the wire for store
     * resolutions (presence-shaped, so false is the fail-soft default
     * of an older backend).
     */
    val androidIsOta: Boolean,
)

/** One cursor page of the catalog. */
data class CatalogPage(
    val cards: List<CatalogCard>,
    val nextCursor: String?,
)

/** One authorized version entry of the history (S8-03's eligibility listing). */
data class HistoryEntry(
    val versionId: String,
    val versionName: String,
    val status: String,
    val androidArtifactId: String?,
    val androidBuildNumber: Long?,
)

/** One ALLOWED download issuance (S8-04): the routed URL, its facts, and the telemetry pairing. */
data class DownloadIssuance(
    val artifactId: String,
    val versionId: String,
    val url: String,
    val expiresAt: String?,
    val fileName: String?,
    val sizeBytes: Long,
    val telemetry: TelemetryIssuance?,
)

/** The telemetry block of a paired download — the sdt1 token the SDK would configure. */
data class TelemetryIssuance(
    val token: String,
    val tenantId: String,
    val applicationId: String,
    val releaseId: String,
    val versionId: String,
)

/** Parses the session, catalog, history, and issuance shapes. Fail-soft on unknown members. */
internal object PortalModelsJson {

    fun tokenPair(answer: String): TokenPair {
        val root = root(answer)
        return TokenPair(
            accessToken = requiredString(root, "accessToken"),
            refreshToken = requiredString(root, "refreshToken"),
            expiresInSeconds = requiredLong(root, "expiresInSeconds"),
        )
    }

    fun catalogPage(answer: String): CatalogPage {
        val root = root(answer)
        val items = (root["items"] as? List<*>) ?: throw MalformedAnswer("catalog items missing")
        val cards = items.mapNotNull { item ->
            val card = item as? Map<*, *> ?: throw MalformedAnswer("catalog item is not an object")
            val application = card["application"] as? Map<*, *>
                ?: throw MalformedAnswer("catalog application missing")
            val platforms = (card["platforms"] as? List<*>) ?: emptyList<Any?>()
            val android = platforms.mapNotNull { it as? Map<*, *> }.firstOrNull { it["platform"] == "ANDROID" }
            CatalogCard(
                tenantId = requiredString(card, "tenantId"),
                applicationId = requiredString(application, "applicationId"),
                applicationName = optionalString(application, "name") ?: "(unnamed)",
                latestVersionId = optionalString(card["latestAuthorizedVersion"] as? Map<*, *>, "versionId"),
                latestVersionName = optionalString(card["latestAuthorizedVersion"] as? Map<*, *>, "versionName"),
                androidVersionId = optionalString(android, "versionId"),
                androidVersionName = optionalString(android, "versionName"),
                androidIsOta = (android?.get("ota") as? Boolean) == true,
            )
        }
        return CatalogPage(cards = cards, nextCursor = optionalString(root, "next_cursor"))
    }

    fun historyEntries(answer: String): List<HistoryEntry> {
        val root = root(answer)
        val entries = (root["entries"] as? List<*>) ?: throw MalformedAnswer("history entries missing")
        return entries.map { item ->
            val entry = item as? Map<*, *> ?: throw MalformedAnswer("history entry is not an object")
            val platforms = (entry["platforms"] as? List<*>) ?: emptyList<Any?>()
            val android = platforms.mapNotNull { it as? Map<*, *> }.firstOrNull { it["platform"] == "ANDROID" }
            HistoryEntry(
                versionId = requiredString(entry, "versionId"),
                versionName = requiredString(entry, "versionName"),
                status = optionalString(entry, "status") ?: "UNKNOWN",
                androidArtifactId = optionalString(android, "artifactId"),
                androidBuildNumber = optionalLong(android, "buildNumber"),
            )
        }
    }

    fun downloadIssuance(answer: String): DownloadIssuance {
        val root = root(answer)
        val telemetryBlock = root["telemetry"] as? Map<*, *>
        return DownloadIssuance(
            artifactId = requiredString(root, "artifactId"),
            versionId = requiredString(root, "versionId"),
            url = requiredString(root, "url"),
            expiresAt = optionalString(root, "expiresAt"),
            fileName = optionalString(root, "fileName"),
            sizeBytes = optionalLong(root, "sizeBytes") ?: 0L,
            telemetry = telemetryBlock?.let {
                TelemetryIssuance(
                    token = requiredString(it, "token"),
                    tenantId = requiredString(it, "tenantId"),
                    applicationId = requiredString(it, "applicationId"),
                    releaseId = requiredString(it, "releaseId"),
                    versionId = requiredString(it, "versionId"),
                )
            },
        )
    }

    private fun root(answer: String): Map<String, Any?> =
        Json.parse(answer) as? Map<String, Any?> ?: throw MalformedAnswer("answer is not a JSON object")

    private fun requiredString(root: Map<*, *>, name: String): String =
        optionalString(root, name) ?: throw MalformedAnswer("$name missing")

    private fun optionalString(root: Map<*, *>?, name: String): String? =
        (root?.get(name) as? String)?.takeIf { it.isNotBlank() }

    private fun optionalLong(root: Map<*, *>?, name: String): Long? = when (val value = root?.get(name)) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        else -> null
    }

    private fun requiredLong(root: Map<*, *>, name: String): Long =
        optionalLong(root, name) ?: throw MalformedAnswer("$name missing")
}
