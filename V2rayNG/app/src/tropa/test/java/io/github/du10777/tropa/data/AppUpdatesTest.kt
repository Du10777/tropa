package io.github.du10777.tropa.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdatesTest {
    private fun asset(name: String) = """{"name":"$name","browser_download_url":"https://github.com/Du10777/tropa/releases/download/v0.10.0/$name",
        "size":100,"digest":"sha256:ABCDEF"}"""

    private val release = """{"tag_name":"v0.10.0","html_url":"https://github.com/Du10777/tropa/releases/tag/v0.10.0",
        "draft":false,"prerelease":false,"assets":[
        ${asset("tropa_0.10.0_arm64-v8a.apk")},${asset("tropa_0.10.0_armeabi-v7a.apk")},${asset("tropa_0.10.0_universal.apk")},
        ${asset("tropa_0.10.0_x86.apk")},${asset("tropa_0.10.0_x86_64.apk")}]}"""

    @Test
    fun `release of the github answer with the build for this processor`() {
        val parsed = AppUpdates.parse(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!
        assertEquals("0.10.0", parsed.version)
        assertEquals("https://github.com/Du10777/tropa/releases/tag/v0.10.0", parsed.page)
        assertEquals("tropa_0.10.0_arm64-v8a.apk", parsed.apk?.name)
        assertEquals(100L, parsed.apk?.size)
        assertEquals("abcdef", parsed.apk?.sha256)
    }

    @Test
    fun `x86 does not take the x86_64 build, unknown processors get the universal one`() {
        assertEquals("tropa_0.10.0_x86.apk", AppUpdates.parse(release, listOf("x86"))?.apk?.name)
        assertEquals("tropa_0.10.0_universal.apk", AppUpdates.parse(release, listOf("riscv64"))?.apk?.name)
    }

    @Test
    fun `drafts, pre-releases and broken answers are not offered`() {
        assertNull(AppUpdates.parse(release.replace("\"prerelease\":false", "\"prerelease\":true"), listOf("arm64-v8a")))
        assertNull(AppUpdates.parse("{\"message\":\"Not Found\"}", listOf("arm64-v8a")))
        assertNull(AppUpdates.parse("not json", listOf("arm64-v8a")))
    }

    @Test
    fun `versions compare by number, not as text`() {
        assertTrue(AppUpdates.isNewer("0.10.0", "0.9.0"))
        assertTrue(AppUpdates.isNewer("1.0", "0.99.99"))
        assertTrue(AppUpdates.isNewer("0.9.1", "0.9"))
        assertFalse(AppUpdates.isNewer("0.9.0", "0.9.0"))
        assertFalse(AppUpdates.isNewer("0.9.0", "0.10.0"))
    }
}
