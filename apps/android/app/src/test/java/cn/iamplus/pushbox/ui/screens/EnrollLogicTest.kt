package cn.iamplus.pushbox.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollLogicTest {

    @Test
    fun buildBaseUrlKeepsNonDefaultPortsAndDropsDefaultOnes() {
        assertEquals("http://10.0.0.2:7404", buildBaseUrl("10.0.0.2", "7404", false))
        assertEquals("http://10.0.0.2", buildBaseUrl("10.0.0.2", "80", false))
        assertEquals("https://10.0.0.2", buildBaseUrl("10.0.0.2", "443", true))
        assertEquals("https://10.0.0.2:7404", buildBaseUrl("10.0.0.2", "7404", true))
    }

    @Test
    fun buildBaseUrlTrimsSurroundingWhitespaceAndTrailingSlash() {
        assertEquals("http://10.0.0.2:7404", buildBaseUrl(" 10.0.0.2/ ", " 7404 ", false))
        assertEquals("", buildBaseUrl("  ", "7404", false))
        assertEquals("http://10.0.0.2", buildBaseUrl("10.0.0.2", "", false))
    }

    @Test
    fun parseServerUrlSplitsHistoryEntryBackIntoThreeFields() {
        assertEquals(ServerParts(https = true, host = "10.0.0.2", port = "7404"),
            parseServerUrl("https://10.0.0.2:7404/path"))
        assertEquals(ServerParts(https = false, host = "10.0.0.2", port = ""),
            parseServerUrl("http://10.0.0.2"))
        assertEquals(ServerParts(https = true, host = "example.com", port = ""),
            parseServerUrl("https://example.com/"))
    }

    @Test
    fun applyUrlRoundTripsWhatBuildBaseUrlProduces() {
        // buildBaseUrl → parseServerUrl → buildBaseUrl 应回到同一条 URL：
        // 回填三格再推导，不能因为丢端口/协议而变成另一条
        val url = buildBaseUrl("10.0.0.2", "7404", true)
        val parts = parseServerUrl(url)
        assertEquals(url, buildBaseUrl(parts.host, parts.port, parts.https))
    }

    @Test
    fun serverUrlRegexAcceptsWhatTheFormBuildsAndRejectsOthers() {
        assertTrue(SERVER_URL.matches("http://10.0.0.2:7404"))
        assertTrue(SERVER_URL.matches("https://example.com/"))
        assertTrue(SERVER_URL.matches("http://pushbox.example.cn:7404"))
        assertFalse(SERVER_URL.matches("ftp://10.0.0.2"))
        assertFalse(SERVER_URL.matches("10.0.0.2"))
        assertFalse(SERVER_URL.matches("http://"))
    }
}
