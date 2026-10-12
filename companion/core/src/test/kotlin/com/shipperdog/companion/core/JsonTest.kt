package com.shipperdog.companion.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The minimal JSON reader's own suite (issue #73, S15-02): the parser
 * is hand-rolled, so its edges are pinned here — the closed response
 * vocabulary's shapes, the escape set, integral-vs-fractional
 * numbers, and the malformed inputs the server must never answer
 * with but a proxy in front of it might.
 */
class JsonTest {

    @Test
    fun parsesTheSessionShape() {
        val value = Json.parse("""{"accessToken":"at","refreshToken":"rt","tokenType":"Bearer","expiresInSeconds":900}""")
        val root = value as Map<*, *>
        assertEquals("at", root["accessToken"])
        assertEquals("rt", root["refreshToken"])
        assertEquals(900L, root["expiresInSeconds"])
    }

    @Test
    fun parsesNestedCatalogShape() {
        val value = Json.parse(
            """
            {"effectiveAccess":true,"items":[
              {"tenantId":"t1","application":{"applicationId":"a1","name":"Field Ops","status":"ACTIVE"},
               "latestAuthorizedVersion":{"versionId":"v9","versionName":"2.1.0","status":"ACTIVE"},
               "platforms":[{"platform":"IOS","tenantId":"t1","versionId":"v8","versionName":"2.0.0"},
                            {"platform":"ANDROID","tenantId":"t1","versionId":"v9","versionName":"2.1.0"}]}
            ],"next_cursor":null}
            """.trimIndent(),
        )
        val root = value as Map<*, *>
        val items = root["items"] as List<*>
        assertEquals(1, items.size)
        val card = items[0] as Map<*, *>
        val application = card["application"] as Map<*, *>
        assertEquals("Field Ops", application["name"])
        val platforms = card["platforms"] as List<*>
        assertEquals(2, platforms.size)
        assertEquals(null, root["next_cursor"])
    }

    @Test
    fun answersLongForIntegralAndDoubleForFractional() {
        val root = Json.parse("""{"size":1048576,"ratio":0.5,"negative":-3,"exp":1e3}""") as Map<*, *>
        assertEquals(1048576L, root["size"])
        assertEquals(0.5, root["ratio"])
        assertEquals(-3L, root["negative"])
        assertEquals(1000.0, root["exp"])
    }

    @Test
    fun decodesTheEscapeSet() {
        val root = Json.parse(
            """{"quote":"a\"b","slash":"a\\b","nl":"a\nb","tab":"a\tb","u":"aéb","f":"a\fb"}""",
        ) as Map<*, *>
        assertEquals("a\"b", root["quote"])
        assertEquals("a\\b", root["slash"])
        assertEquals("a\nb", root["nl"])
        assertEquals("a\tb", root["tab"])
        assertEquals("aéb", root["u"])
        assertEquals("a\u000Cb", root["f"])
    }

    @Test
    fun parsesEmptyCollectionsAndLiterals() {
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
        assertEquals(emptyMap<String, Any?>(), Json.parse("{}"))
        assertEquals(null, Json.parse("null"))
        assertEquals(true, Json.parse("true"))
        assertEquals(false, Json.parse("false"))
    }

    @Test
    fun rejectsMalformedDocuments() {
        assertFailsWith<Json.JsonException> { Json.parse("{") }
        assertFailsWith<Json.JsonException> { Json.parse("""{"a":}""") }
        assertFailsWith<Json.JsonException> { Json.parse("""["unclosed""") }
        assertFailsWith<Json.JsonException> { Json.parse("""{"a":1}trailing""") }
        assertFailsWith<Json.JsonException> { Json.parse("nul") }
        assertFailsWith<Json.JsonException> { Json.parse("""{"a":"\q"}""") }
    }

    @Test
    fun writesRequestBodies() {
        val body = Json.write(mapOf("email" to "t@shipperdog.local", "password" to "p\"a\\ss"))
        assertEquals("""{"email":"t@shipperdog.local","password":"p\"a\\ss"}""", body)
        assertTrue(Json.write(emptyMap()).equals("{}"))
    }

    @Test
    fun emptyInputRefusesLoudly() {
        assertFailsWith<Json.JsonException> { Json.parse("") }
        assertFailsWith<Json.JsonException> { Json.parse("   \t\n") }
    }

    @Test
    fun structuralBreaksRefuseLoudly() {
        val malformed = listOf(
            "{\"a\"", // member name never closed
            "{\"a\" 1}", // ':' missing
            "{\"a\":1 \"b\":2}", // separator missing
            "{1:2}", // member name is not a string
            "[1,]", // element missing after the comma
            "[1 2]", // separator missing
            "falsey", // literal with trailing junk
            "-", // sign with no digits
            "1.2.3", // the second dot is trailing content
            "null null", // a second document
        )
        malformed.forEach { input ->
            assertFailsWith<Json.JsonException>("must refuse: <$input>") { Json.parse(input) }
        }
    }

    @Test
    fun stringBreaksRefuseLoudly() {
        assertFailsWith<Json.JsonException> { Json.parse("\"dangling escape \\") }
        assertFailsWith<Json.JsonException> { Json.parse("\"truncated \\u00e\"") }
        assertFailsWith<Json.JsonException> { Json.parse("\"non-hex \\uZZZZ\"") }
        assertFailsWith<Json.JsonException> {
            Json.parse("\"raw <newline> control\"".replace("<newline>", "\n"))
        }
    }

    @Test
    fun beyondLongIntegralsDegradeToDoubleNeverCrash() {
        val root = Json.parse("""{"huge":99999999999999999999}""") as Map<*, *>
        assertEquals(1.0E20, root["huge"])
    }

    @Test
    fun objectMembersKeepInsertionOrderAndLastWriteWins() {
        val root = Json.parse("""{"b":1,"a":2,"b":3,"c":null}""") as Map<*, *>
        assertEquals(listOf("b", "a", "c"), root.keys.toList())
        assertEquals(3L, root["b"])
        assertTrue("c" in root, "an explicit null member is present, not absent")
    }

    @Test
    fun whitespaceBetweenTokensIsToleratedEverywhere() {
        val root = Json.parse("  { \"a\" : [ 1 , 2 ] , \"b\" : true }  ") as Map<*, *>
        assertEquals(listOf(1L, 2L), root["a"])
        assertEquals(true, root["b"])
    }

    @Test
    fun serverPlausibleNestingParsesRecursively() {
        // 256 levels: far beyond any answer the frozen contracts produce;
        // pins that ordinary recursion depth is never the failure mode.
        val deep = "[".repeat(256) + "1" + "]".repeat(256)
        var value: Any? = Json.parse(deep)
        var depth = 0
        while (value is List<*>) {
            value = value.single()
            depth++
        }
        assertEquals(256, depth)
        assertEquals(1L, value)
    }
}
