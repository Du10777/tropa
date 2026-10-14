package io.github.du10777.tropa.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalProxyTest {
    // Inbounds of a 3X-UI JSON subscription: open socks 10808 and http 10809.
    private val config = """{"inbounds":[
        {"listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth","udp":true,"userLevel":8},
         "sniffing":{"destOverride":["http","tls","quic","fakedns"],"enabled":true},"tag":"mixed"},
        {"listen":"127.0.0.1","port":10809,"protocol":"http","settings":{"userLevel":8},"tag":"http"},
        {"listen":"127.0.0.1","port":10853,"protocol":"dokodemo-door","tag":"dns-in"}],
      "outbounds":[{"protocol":"freedom","tag":"direct"}]}"""

    private fun inbounds(json: String) = JsonParser.parseString(json).asJsonObject.getAsJsonArray("inbounds").map { it.asJsonObject }

    private fun JsonObject.account() = getAsJsonObject("settings").getAsJsonArray("accounts")?.get(0)?.asJsonObject

    @Test
    fun `open local inbounds are replaced by the protected internal one`() {
        val result = inbounds(LocalProxy.patch(config, LocalProxy.Internal(34567, "u1", "p1"), null))
        assertEquals(listOf("dokodemo-door", "socks"), result.map { it.get("protocol").asString })
        val socks = result.last()
        assertEquals("mixed", socks.get("tag").asString) // routing rules may name the original tag
        assertEquals(34567, socks.get("port").asInt)
        assertEquals("127.0.0.1", socks.get("listen").asString)
        assertEquals("password", socks.getAsJsonObject("settings").get("auth").asString)
        assertEquals("u1", socks.account()!!.get("user").asString)
        assertEquals("p1", socks.account()!!.get("pass").asString)
        assertEquals(8, socks.getAsJsonObject("settings").get("userLevel").asInt)
        assertTrue(socks.getAsJsonObject("sniffing").get("enabled").asBoolean)
        assertFalse(LocalProxy.patch(config, LocalProxy.Internal(34567, "u1", "p1"), null).contains("10808"))
    }

    @Test
    fun `the user's proxy is added when it is on`() {
        val user = LocalProxy.UserProxy(true, 25000, "me", "secret")
        val local = inbounds(LocalProxy.patch(config, LocalProxy.Internal(34567, "u1", "p1"), user)).last()
        assertEquals(LocalProxy.USER_TAG, local.get("tag").asString)
        assertEquals(25000, local.get("port").asInt)
        assertEquals("me", local.account()!!.get("user").asString)
        val open = inbounds(LocalProxy.patch(config, LocalProxy.Internal(34567, "u1", "p1"), user.copy(user = "", pass = ""))).last()
        assertEquals("noauth", open.getAsJsonObject("settings").get("auth").asString)
    }

    @Test
    fun `a config without inbounds gets the internal one, broken configs stay as they are`() {
        val result = inbounds(LocalProxy.patch("""{"outbounds":[]}""", LocalProxy.Internal(40000, "a", "b"), null))
        assertEquals(1, result.size)
        assertEquals("socks", result[0].get("tag").asString)
        assertEquals("not json", LocalProxy.patch("not json", LocalProxy.Internal(40000, "a", "b"), null))
    }
}
