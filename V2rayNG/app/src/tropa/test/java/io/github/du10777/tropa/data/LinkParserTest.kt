package io.github.du10777.tropa.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkParserTest {

    @Test
    fun `xsub link decodes to an https subscription`() {
        val candidate = LinkParser.parse("xsub://ZXhhbXBsZS5jb20vaS9BYkMxMjM")
        assertEquals(LinkCandidate.Subscription("https://example.com/i/AbC123", "xsub://ZXhhbXBsZS5jb20vaS9BYkMxMjM"), candidate)
    }

    @Test
    fun `xsub link is found inside a whole copied message`() {
        val message = "Привет! Вот твоя ссылка:\nxsub://ZXhhbXBsZS5jb20vaS9BYkMxMjM\nВставь её в Тропу."
        val candidate = LinkParser.parse(message) as LinkCandidate.Subscription
        assertEquals("https://example.com/i/AbC123", candidate.url)
    }

    @Test
    fun `only xsub links are offered from the clipboard`() {
        assertEquals(
            "https://example.com/i/AbC123",
            LinkParser.findTropaLink("Вот ссылка: xsub://ZXhhbXBsZS5jb20vaS9BYkMxMjM, вставь в Тропу")?.url,
        )
        assertNull(LinkParser.findTropaLink("https://domain.com"))
        assertNull(LinkParser.findTropaLink("https://domain.com/sub/abc"))
        assertNull(LinkParser.findTropaLink("vless://uuid@host:443?type=tcp#x"))
        assertNull(LinkParser.findTropaLink("xsub://!!!"))
        assertNull(LinkParser.findTropaLink(null))
    }

    @Test
    fun `xsub accepts the standard alphabet, padding and an explicit scheme`() {
        val payload = Base64Codec.encodeUrlSafe("http://10.0.0.1:2096/sub/abc?x=1".toByteArray())
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        assertEquals("http://10.0.0.1:2096/sub/abc?x=1", LinkParser.decodeXsub(padded.replace('-', '+').replace('_', '/')))
    }

    @Test
    fun `encode and decode are inverse`() {
        val url = "https://animefox.example.org/i/Kx7fQ2"
        val link = LinkParser.encodeXsub(url)
        assertTrue(link.startsWith("xsub://"))
        assertTrue(!link.contains('.'), "the link must not reveal a domain")
        assertEquals(url, LinkParser.decodeXsub(link.removePrefix("xsub://")))
    }

    @Test
    fun `broken xsub payload is ignored`() {
        assertNull(LinkParser.decodeXsub("!!!"))
        assertNull(LinkParser.parse("xsub://"))
    }

    @Test
    fun `share links are extracted from surrounding text`() {
        val text = "Держи: vless://uuid@host:443?security=reality#Kazan и ещё trojan://pw@host2:443#B, спасибо"
        val candidate = LinkParser.parse(text) as LinkCandidate.Configs
        assertEquals("vless://uuid@host:443?security=reality#Kazan\ntrojan://pw@host2:443#B", candidate.text)
    }

    @Test
    fun `https subscription loses trailing punctuation`() {
        val candidate = LinkParser.parse("Подписка: https://example.com/sub/token123.") as LinkCandidate.Subscription
        assertEquals("https://example.com/sub/token123", candidate.url)
    }

    @Test
    fun `v2rayng install-sub link becomes a subscription`() {
        val candidate = LinkParser.parse("v2rayng://install-sub?url=https%3A%2F%2Fexample.com%2Fsub%2Fa%2Bb") as LinkCandidate.Subscription
        assertEquals("https://example.com/sub/a+b", candidate.url)
    }

    @Test
    fun `full json config is imported as configs`() {
        val json = """{"inbounds":[],"outbounds":[{"protocol":"freedom"}],"routing":{}}"""
        assertEquals(LinkCandidate.Configs(json), LinkParser.parse(json))
    }

    @Test
    fun `base64 subscription body is imported as configs`() {
        val body = Base64Codec.encodeUrlSafe("vless://a@b:443#one\nvmess://xyz".toByteArray())
        assertTrue(LinkParser.parse(body) is LinkCandidate.Configs)
    }

    @Test
    fun `text without a connection gives nothing`() {
        assertNull(LinkParser.parse("просто текст без ссылок"))
        assertNull(LinkParser.parse("   "))
        assertNull(LinkParser.parse(null))
    }

    @Test
    fun `base64 codec round trips binary data`() {
        val data = ByteArray(256) { it.toByte() }
        assertArrayEquals(data, Base64Codec.decode(Base64Codec.encodeUrlSafe(data)))
    }
}

class SubscriptionInfoTest {

    @Test
    fun `userinfo header is parsed`() {
        val info = SubscriptionInfo.parse("upload=1000; download=2000; total=10000; expire=1767225600")!!
        assertEquals(3000L, info.used)
        assertEquals(10000L, info.total)
        assertEquals(1767225600L, info.expire)
        assertEquals(0.3f, info.usedFraction!!, 0.0001f)
    }

    @Test
    fun `no limit means no fraction`() {
        val info = SubscriptionInfo.parse("upload=5; download=5; total=0; expire=0")!!
        assertNull(info.usedFraction)
    }

    @Test
    fun `garbage header is ignored`() {
        assertNull(SubscriptionInfo.parse("nonsense"))
        assertNull(SubscriptionInfo.parse(""))
    }

    @Test
    fun `profile title supports base64`() {
        assertEquals("Тропа", SubscriptionInfo.decodeTitle("base64:0KLRgNC+0L/QsA=="))
        assertEquals("My VPN", SubscriptionInfo.decodeTitle("My VPN"))
        assertNull(SubscriptionInfo.decodeTitle("  "))
    }
}
