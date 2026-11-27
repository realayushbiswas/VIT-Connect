package com.offgridmesh.vitconnect.mesh.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySandboxTest {

    private class FakeKeys(override val braveKey: String? = "brave-secret", override val anthropicKey: String? = "claude-secret") : ApiKeys

    private class FakeHttp(private val result: HttpResult) : HttpClient {
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String? = null
        var calls = 0

        override fun request(url: String, method: String, headers: Map<String, String>, body: String?): HttpResult {
            calls++
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return result
        }
    }

    private fun search(text: String) = QueryRequest("rid1", QueryKind.SEARCH, text)
    private fun ask(text: String) = QueryRequest("rid2", QueryKind.ASK_AI, text)

    private val braveJson = """
        {"web":{"results":[
          {"title":"<strong>Kotlin</strong> &amp; Android","url":"https://kotlinlang.org","description":"A <b>modern</b> language&#39;s home"},
          {"title":"Evil","url":"javascript:alert(1)","description":"x"}
        ]}}
    """.trimIndent()

    @Test
    fun searchFormatsResultsAsPlainText() {
        val http = FakeHttp(HttpResult(200, braveJson))
        val response = QuerySandbox(FakeKeys(), http).execute(search("kotlin"))
        assertTrue(response.ok)
        assertTrue(response.text.contains("1. Kotlin & Android"))
        assertTrue(response.text.contains("A modern language's home"))
        assertTrue(response.text.contains("https://kotlinlang.org"))
        assertFalse(response.text.contains("<"))
        // A non-http(s) link must never be passed along.
        assertFalse(response.text.contains("javascript:"))
    }

    @Test
    fun searchSendsKeyOnlyUpstreamAndEncodesTheQuery() {
        val http = FakeHttp(HttpResult(200, braveJson))
        QuerySandbox(FakeKeys(), http).execute(search("a&b=c d"))
        assertTrue(QuerySandbox.isAllowedUrl(http.lastUrl!!))
        assertTrue(http.lastUrl!!.contains("q=a%26b%3Dc+d"))
        assertEquals("brave-secret", http.lastHeaders["X-Subscription-Token"])
    }

    @Test
    fun askAiSendsQuestionAsJsonDataAndReturnsText() {
        val http = FakeHttp(HttpResult(200, """{"content":[{"type":"text","text":"Hello <script>x</script> there"}]}"""))
        val response = QuerySandbox(FakeKeys(), http).execute(ask("""say "hi"} {"model":"evil"""))
        assertTrue(response.ok)
        assertEquals("Hello x there", response.text)
        // The question stayed a JSON string value; it could not change the model or any field.
        val sent = JSONObject(http.lastBody!!)
        assertEquals(QuerySandbox.AI_MODEL, sent.getString("model"))
        assertEquals("""say "hi"} {"model":"evil""", sent.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("claude-secret", http.lastHeaders["x-api-key"])
    }

    @Test
    fun missingKeyFailsWithoutCallingOut() {
        val http = FakeHttp(HttpResult(200, braveJson))
        val response = QuerySandbox(FakeKeys(braveKey = null), http).execute(search("x"))
        assertFalse(response.ok)
        assertEquals(0, http.calls)
    }

    @Test
    fun upstreamErrorsNeverLeakBodiesOrKeys() {
        val http = FakeHttp(HttpResult(401, """{"error":"bad key brave-secret"}"""))
        val response = QuerySandbox(FakeKeys(), http).execute(search("x"))
        assertFalse(response.ok)
        assertFalse(response.text.contains("brave-secret"))
        assertEquals(false, QuerySandbox(FakeKeys(), FakeHttp(HttpResult(429, ""))).execute(search("x")).ok)
    }

    @Test
    fun networkFailureBecomesAFailedResponseNotACrash() {
        val throwing = object : HttpClient {
            override fun request(url: String, method: String, headers: Map<String, String>, body: String?): HttpResult =
                throw java.io.IOException("boom")
        }
        val response = QuerySandbox(FakeKeys(), throwing).execute(search("x"))
        assertFalse(response.ok)
    }

    @Test
    fun garbledUpstreamJsonBecomesAFailedResponse() {
        val response = QuerySandbox(FakeKeys(), FakeHttp(HttpResult(200, "not json"))).execute(search("x"))
        assertFalse(response.ok)
    }

    @Test
    fun emptyOrOversizedQueriesAreRejectedWithoutCallingOut() {
        val http = FakeHttp(HttpResult(200, braveJson))
        val sandbox = QuerySandbox(FakeKeys(), http)
        assertFalse(sandbox.execute(search("   ")).ok)
        assertFalse(sandbox.execute(search("x".repeat(GatewayLimits.MAX_QUERY_CHARS + 1))).ok)
        assertEquals(0, http.calls)
    }

    @Test
    fun answersAreCappedInBytes() {
        val big = "é".repeat(20_000)
        val http = FakeHttp(HttpResult(200, JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", big))).toString()))
        val response = QuerySandbox(FakeKeys(), http).execute(ask("x"))
        assertTrue(response.text.toByteArray(Charsets.UTF_8).size <= GatewayLimits.MAX_RESPONSE_BYTES)
        assertTrue(response.text.endsWith("…"))
    }

    @Test
    fun onlyTheTwoApiHostsAreAllowed() {
        assertTrue(QuerySandbox.isAllowedUrl("https://api.search.brave.com/res/v1/web/search?q=x"))
        assertTrue(QuerySandbox.isAllowedUrl("https://api.anthropic.com/v1/messages"))
        assertFalse(QuerySandbox.isAllowedUrl("http://api.search.brave.com/res/v1/web/search?q=x"))
        assertFalse(QuerySandbox.isAllowedUrl("https://api.search.brave.com.evil.com/res/v1/web/search?q=x"))
        assertFalse(QuerySandbox.isAllowedUrl("https://api.search.brave.com/res/v1/web/searchX"))
        assertFalse(QuerySandbox.isAllowedUrl("https://169.254.169.254/latest/meta-data"))
        assertFalse(QuerySandbox.isAllowedUrl("file:///data/data/com.offgridmesh.vitconnect/shared_prefs/x.xml"))
    }

    @Test
    fun plainTextStripsMarkupAndControlCharacters() {
        assertEquals("a & b", QuerySandbox.toPlainText("<i>a</i> &amp; b"))
        assertEquals("&lt;b&gt;", QuerySandbox.toPlainText("&amp;lt;b&amp;gt;"))
        assertEquals("ab", QuerySandbox.toPlainText("a\u0000\u0007b"))
        assertEquals("one\n\ntwo", QuerySandbox.toPlainText("one\n\n\n\n\ntwo"))
    }

    @Test
    fun truncateNeverSplitsAMultibyteCharacter() {
        val s = "😀".repeat(100) // 4 bytes each
        val cut = QuerySandbox.truncateUtf8(s, 50)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 50)
        assertFalse(cut.contains('�'))
        assertEquals("short", QuerySandbox.truncateUtf8("short", 50))
    }

    @Test
    fun cleanQueryTrimsAndValidates() {
        assertEquals("hi", QuerySandbox.cleanQuery("  hi\u0000 "))
        assertNull(QuerySandbox.cleanQuery(""))
        assertNull(QuerySandbox.cleanQuery("y".repeat(301)))
        assertNotNull(QuerySandbox.cleanQuery("y".repeat(300)))
    }

    @Test
    fun protocolMessagesRoundTrip() {
        val req = QueryRequest("abc123", QueryKind.ASK_AI, "why is the sky blue?")
        assertEquals(req, QueryRequest.fromJson(req.toJson()))
        val resp = QueryResponse("abc123", true, "Rayleigh scattering.\nSecond line")
        assertEquals(resp, QueryResponse.fromJson(resp.toJson()))
        assertEquals(setOf(QueryKind.SEARCH), GatewayAnnounce.decode(GatewayAnnounce.encode(setOf(QueryKind.SEARCH))))
    }

    @Test
    fun malformedProtocolMessagesAreRejected() {
        assertNull(QueryRequest.fromJson("garbage"))
        assertNull(QueryRequest.fromJson("""{"rid":"a","kind":"RUN_SHELL","q":"ls"}"""))
        assertNull(QueryRequest.fromJson("""{"rid":"","kind":"SEARCH","q":"x"}"""))
        assertNull(QueryResponse.fromJson("""{"rid":"a"}"""))
        assertTrue(GatewayAnnounce.decode("garbage").isEmpty())
    }
}
