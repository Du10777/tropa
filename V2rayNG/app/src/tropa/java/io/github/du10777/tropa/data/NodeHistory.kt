package io.github.du10777.tropa.data

import com.google.gson.Gson
import com.v2ray.ang.handler.MmkvManager

/**
 * What Tropa knows about a server from earlier sessions: its last checks (times and answers), the
 * user's connections through it per hour with the failed ones, and its last speeds. Kept for a week
 * (the window of [NodeSelector]), per server: the fingerprint of its settings, so the same server in a
 * new version of a subscription keeps its history and a changed one starts afresh. It also starts
 * afresh when the server's name moves to another IP ([ip], its LastIP when the history was written).
 */
data class NodeHistory(
    val samples: List<Sample> = emptyList(),
    val outcomes: List<Outcome> = emptyList(),
    val buckets: List<Bucket> = emptyList(),
    val speedKbps: Long? = null,
    val uploadKbps: Long? = null,
    val ip: String? = null,
    /** The node's gateway from its self-test answers (see [NodeSelector.Stats.gateway]). */
    val gateway: String? = null,
    /** The exit its node names (see [NodeSelector.Stats.exit]). */
    val exit: String? = null,
) {
    data class Sample(val at: Long, val latencyMs: Long, val fetchMs: Long?)
    data class Outcome(val at: Long, val ok: Boolean)
    data class Bucket(val hour: Long, val conns: Long, val fails: Long)

    val isEmpty: Boolean get() = samples.isEmpty() && outcomes.isEmpty() && buckets.isEmpty()
}

/** Server histories in the multi-process settings storage, one entry per server fingerprint. */
object NodeHistoryStore {
    private const val PREFIX = "tropa_node_history_"
    private val gson = Gson()

    fun load(fingerprint: String): NodeHistory? =
        MmkvManager.decodeSettingsString(PREFIX + fingerprint)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { gson.fromJson(it, NodeHistory::class.java) }.getOrNull() }

    fun save(fingerprint: String, history: NodeHistory) {
        MmkvManager.encodeSettings(PREFIX + fingerprint, if (history.isEmpty) "" else gson.toJson(history))
    }
}
