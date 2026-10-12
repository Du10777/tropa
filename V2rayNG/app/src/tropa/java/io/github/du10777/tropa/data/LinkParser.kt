package io.github.du10777.tropa.data

import java.net.URI
import java.net.URLDecoder

/** What a piece of text pasted, scanned or shared into Tropa turns out to be. */
sealed interface LinkCandidate {
    /** A subscription to download from [url]; [source] is the link exactly as the user had it. */
    data class Subscription(val url: String, val source: String) : LinkCandidate

    /** Ready configurations: share links (vless://, …) or a full Xray JSON config. */
    data class Configs(val text: String) : LinkCandidate
}

/**
 * Finds a connection in arbitrary text, so a whole copied message can be pasted as is.
 *
 * Tropa links look like `xsub://<base64url of the subscription address>`: there are no dots and no
 * domain in them, so messengers neither turn them into clickable links nor fetch previews (which
 * would burn one-time tokens). `https://` is implied when the decoded address has no scheme.
 */
object LinkParser {
    private val XSUB = Regex("""xsub://([A-Za-z0-9_\-+/]+={0,2})""", RegexOption.IGNORE_CASE)
    private val V2RAYNG_INSTALL = Regex("""v2rayng://install-(?:sub|config)\?url=(\S+)""", RegexOption.IGNORE_CASE)
    private val CONFIG_LINK = Regex(
        """\b(?:vless|vmess|trojan|ss|socks|socks4|socks5|wireguard|tuic|hysteria|hysteria2|hy2|v2rayn)://\S+""",
        RegexOption.IGNORE_CASE
    )
    private val WEB_URL = Regex("""https?://[^\s<>"'`«»]+""", RegexOption.IGNORE_CASE)
    private const val URL_TRAILING_PUNCTUATION = ".,;:!?)]}"

    fun parse(text: String?): LinkCandidate? {
        val input = text?.trim().orEmpty()
        if (input.isEmpty()) return null

        XSUB.find(input)?.let { match ->
            decodeXsub(match.groupValues[1])?.let { return LinkCandidate.Subscription(it, match.value) }
        }
        V2RAYNG_INSTALL.find(input)?.let { match ->
            val url = URLDecoder.decode(match.groupValues[1].replace("+", "%2B"), "UTF-8")
            if (isWebUrl(url)) return LinkCandidate.Subscription(url, match.value)
        }
        if (looksLikeJsonConfig(input)) return LinkCandidate.Configs(input)

        val links = CONFIG_LINK.findAll(input).map { it.value.trimEnd { c -> c in URL_TRAILING_PUNCTUATION } }.toList()
        if (links.isNotEmpty()) return LinkCandidate.Configs(links.joinToString("\n"))

        // A pasted subscription body: base64 of share links.
        val decoded = Base64Codec.decode(input)?.toString(Charsets.UTF_8)
        if (!input.contains(' ') && decoded != null && CONFIG_LINK.containsMatchIn(decoded)) {
            return LinkCandidate.Configs(input)
        }

        WEB_URL.find(input)?.let { match ->
            val url = match.value.trimEnd { it in URL_TRAILING_PUNCTUATION }
            if (isWebUrl(url)) return LinkCandidate.Subscription(url, url)
        }
        return null
    }

    /** Decodes the payload of an `xsub://` link into an https (or explicit scheme) address. */
    fun decodeXsub(payload: String): String? {
        val decoded = Base64Codec.decode(payload)?.toString(Charsets.UTF_8)?.trim() ?: return null
        val url = if (decoded.contains("://")) decoded else "https://$decoded"
        return url.takeIf(::isWebUrl)
    }

    /** Encodes an address as an `xsub://` link (the inverse of [decodeXsub]). */
    fun encodeXsub(url: String): String {
        val payload = url.removePrefix("https://")
        return "xsub://" + Base64Codec.encodeUrlSafe(payload.toByteArray(Charsets.UTF_8))
    }

    private fun looksLikeJsonConfig(text: String): Boolean =
        (text.startsWith("{") || text.startsWith("[")) && text.contains("\"outbounds\"")

    private fun isWebUrl(value: String): Boolean = try {
        val uri = URI(value)
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }
}
