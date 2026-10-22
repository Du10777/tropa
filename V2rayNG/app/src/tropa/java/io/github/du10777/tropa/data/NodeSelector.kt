package io.github.du10777.tropa.data

/**
 * Decides which server new connections should use (pure logic, unit tested).
 *
 * - The active server is dead after [failThreshold] failed checks in a row: switch at once to
 *   the best server that answered its last check.
 * - Another server is "noticeably better" when its median time is at most [betterRatio] of the
 *   active one and at least [betterMinMs] lower, [betterRounds] evaluations in a row.
 *
 * The time compared is the time to fetch the 128 KB of the self-test from the server (latency and
 * speed in one number) when every measured server answers the self-test, otherwise the latency:
 * like is compared with like. Each server keeps a short history of its checks instead of only the
 * last one: the medians and the share of answered checks are taken over a sliding window, newest
 * samples bounded both by their count ([maxSamples]) and by their age ([windowMs], a week), so
 * recent measurements decide and a server idle for a week starts fresh. The clock is injected so
 * the window is testable.
 */
class NodeSelector(
    private var failThreshold: Int = CheckSettings.DEFAULTS.failThreshold,
    private var betterRatio: Double = CheckSettings.DEFAULTS.betterRatio,
    private var betterMinMs: Long = CheckSettings.DEFAULTS.betterMinMs.toLong(),
    private var betterRounds: Int = CheckSettings.DEFAULTS.betterRounds,
    private val maxSamples: Int = 16,
    private val windowMs: Long = 7 * 24 * 60 * 60 * 1000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** New thresholds (the check parameters may change while connected); what is known about the servers stays. */
    @Synchronized
    fun tune(values: CheckValues) {
        failThreshold = values.failThreshold
        betterRatio = values.betterRatio
        betterMinMs = values.betterMinMs.toLong()
        betterRounds = values.betterRounds
    }

    sealed interface Decision {
        data object Keep : Decision
        data class SwitchTo(val tag: String, val reason: Reason) : Decision
        data object AllDead : Decision
    }

    enum class Reason { ACTIVE_DEAD, BETTER }

    /** [fetchMs]: the time to fetch the self-test, null for a classic check (latency only). */
    private class Sample(val at: Long, val latencyMs: Long, val fetchMs: Long?)
    private class Outcome(val at: Long, val ok: Boolean)

    /** The user's connections through a server in one hour (the core's counters), and how many failed. */
    private class Bucket(val hour: Long, var conns: Long, var fails: Long)

    inner class Stats {
        private val samples = ArrayDeque<Sample>()
        private val outcomes = ArrayDeque<Outcome>()
        private val buckets = ArrayDeque<Bucket>()
        var consecutiveFails = 0
            private set
        var checks = 0
            private set

        /** Rounds in a row this server looked noticeably better than the active one (used by [evaluate]). */
        var betterStreak = 0

        /** The last speed checks of this server (download, upload) and the user's traffic seen through it, kbit/s. */
        var speedKbps: Long? = null
        var uploadKbps: Long? = null
        var trafficKbps: Long? = null

        /** The last check went through the self-test of the node (null before any check). */
        var selftest: Boolean? = null

        val alive: Boolean get() = checks > 0 && consecutiveFails == 0
        val dead: Boolean get() = consecutiveFails > 0

        /** Median latency (until the first byte of the answer). */
        fun median(): Long? {
            prune()
            if (samples.isEmpty()) return null
            val sorted = samples.map { it.latencyMs }.sorted()
            return sorted[sorted.size / 2]
        }

        /** Median time to fetch the self-test, over the samples that have it. */
        fun fetchMedian(): Long? {
            prune()
            val sorted = samples.mapNotNull { it.fetchMs }.sorted()
            return if (sorted.isEmpty()) null else sorted[sorted.size / 2]
        }

        /** The newest sample is a self-test one (the server is compared by fetch time). */
        internal fun measuredByFetch(): Boolean? {
            prune()
            return samples.lastOrNull()?.let { it.fetchMs != null }
        }

        /**
         * Share of answered checks and of the user's connections that did not fail, in the window
         * (1.0 with none yet: an untried server is not punished).
         */
        fun reliability(): Double {
            prune()
            val conns = buckets.sumOf { it.conns }
            val fails = buckets.sumOf { it.fails }.coerceAtMost(conns)
            val total = outcomes.size + conns
            return if (total == 0L) 1.0 else (outcomes.count { it.ok } + conns - fails).toDouble() / total
        }

        /** Connections of the user through this server and the failed ones (counted by the core) in the window. */
        fun connections(): Pair<Long, Long> {
            prune()
            return buckets.sumOf { it.conns } to buckets.sumOf { it.fails }
        }

        internal fun addConnections(conns: Long, fails: Long) {
            if (conns <= 0 && fails <= 0) return
            val hour = now() / HOUR_MS * HOUR_MS
            val last = buckets.lastOrNull()?.takeIf { it.hour == hour } ?: Bucket(hour, 0, 0).also { buckets.addLast(it) }
            last.conns += conns.coerceAtLeast(0)
            last.fails += fails.coerceAtLeast(0)
            prune()
        }

        /** What is worth keeping for the next session (see [restore]). */
        internal fun snapshot(): NodeHistory {
            prune()
            return NodeHistory(
                samples = samples.map { NodeHistory.Sample(it.at, it.latencyMs, it.fetchMs) },
                outcomes = outcomes.map { NodeHistory.Outcome(it.at, it.ok) },
                buckets = buckets.map { NodeHistory.Bucket(it.hour, it.conns, it.fails) },
                speedKbps = speedKbps,
                uploadKbps = uploadKbps,
            )
        }

        /**
         * The history of earlier sessions: it ranks the server, but the server counts as neither alive
         * nor dead until it is checked now (a cold start always checks).
         */
        internal fun restore(history: NodeHistory) {
            history.samples.takeLast(maxSamples).forEach { samples.addLast(Sample(it.at, it.latencyMs, it.fetchMs)) }
            history.outcomes.takeLast(maxSamples).forEach { outcomes.addLast(Outcome(it.at, it.ok)) }
            history.buckets.forEach { buckets.addLast(Bucket(it.hour, it.conns, it.fails)) }
            speedKbps = history.speedKbps
            uploadKbps = history.uploadKbps
            prune()
        }

        /** A check: answered or not; an answer without a time (the user's traffic flows through) counts as alive. */
        internal fun record(ok: Boolean, latencyMs: Long?, fetchMs: Long?) {
            checks++
            val t = now()
            outcomes.addLast(Outcome(t, ok))
            if (ok) {
                consecutiveFails = 0
                if (latencyMs != null) samples.addLast(Sample(t, latencyMs, fetchMs))
            } else {
                consecutiveFails++
            }
            while (samples.size > maxSamples) samples.removeFirst()
            while (outcomes.size > maxSamples) outcomes.removeFirst()
            prune()
        }

        private fun prune() {
            val cutoff = now() - windowMs
            while (samples.isNotEmpty() && samples.first().at < cutoff) samples.removeFirst()
            while (outcomes.isNotEmpty() && outcomes.first().at < cutoff) outcomes.removeFirst()
            while (buckets.isNotEmpty() && buckets.first().hour + HOUR_MS < cutoff) buckets.removeFirst()
        }
    }

    private val stats = LinkedHashMap<String, Stats>()

    @Synchronized
    fun reset(tags: Collection<String>) {
        stats.clear()
        tags.forEach { stats[it] = Stats() }
    }

    /**
     * Watches [tags] from now on: what is known about the ones watched before is kept, also for
     * a server whose tag changed ([previous]: new tag to the old one); the rest is forgotten.
     */
    @Synchronized
    fun retain(tags: Collection<String>, previous: Map<String, String> = emptyMap()) {
        val known = LinkedHashMap(stats)
        stats.clear()
        tags.forEach { tag -> stats[tag] = known[previous[tag] ?: tag] ?: Stats() }
    }

    @Synchronized
    fun stats(tag: String): Stats? = stats[tag]

    /** The history of a server for the next session. */
    @Synchronized
    fun snapshot(tag: String): NodeHistory? = stats[tag]?.snapshot()

    /** Puts the history of earlier sessions under [tag] (before its first check). */
    @Synchronized
    fun restore(tag: String, history: NodeHistory) {
        stats.getOrPut(tag) { Stats() }.restore(history)
    }

    /** The user's connections through [tag] since the last look, from the core's counters. */
    @Synchronized
    fun addConnections(tag: String, conns: Long, fails: Long) {
        stats[tag]?.addConnections(conns, fails)
    }

    @Synchronized
    fun record(tag: String, ok: Boolean, latencyMs: Long?, fetchMs: Long? = null) {
        stats.getOrPut(tag) { Stats() }.record(ok, latencyMs, fetchMs)
    }

    /** Compared by fetch time only when every server with measurements was measured by the self-test. */
    private fun byFetch(): Boolean {
        val modes = stats.values.mapNotNull { it.measuredByFetch() }
        return modes.isNotEmpty() && modes.all { it }
    }

    /** The time a server is ranked by (see the class comment), null without measurements. */
    private fun rank(s: Stats, fetch: Boolean): Long? = if (fetch) s.fetchMedian() else s.median()

    /** The time a server is ranked by right now, for the log and the details. */
    @Synchronized
    fun rankMs(tag: String): Long? = stats[tag]?.let { rank(it, byFetch()) }

    @Synchronized
    fun isActiveDead(active: String): Boolean = (stats[active]?.consecutiveFails ?: 0) >= failThreshold

    /** Best server that answered its last check: lowest median time, ties broken by reliability. */
    @Synchronized
    fun bestAlive(except: String? = null): String? {
        val fetch = byFetch()
        return stats.filter { (tag, s) -> tag != except && s.alive }
            .minWithOrNull(
                compareBy<Map.Entry<String, Stats>> { rank(it.value, fetch) ?: Long.MAX_VALUE }
                    .thenByDescending { it.value.reliability() },
            )
            ?.key
    }

    @Synchronized
    fun evaluate(active: String): Decision {
        if (isActiveDead(active)) {
            return bestAlive(except = active)?.let { Decision.SwitchTo(it, Reason.ACTIVE_DEAD) } ?: Decision.AllDead
        }
        val fetch = byFetch()
        val activeMedian = stats[active]?.let { rank(it, fetch) } ?: return Decision.Keep
        var winner: String? = null
        stats.forEach { (tag, s) ->
            if (tag == active) return@forEach
            val median = rank(s, fetch)
            // New connections do not move to a server that fails noticeably more often, however fast.
            val steady = s.reliability() >= (stats[active]?.reliability() ?: 1.0) - FLAKIER_BY
            val better = s.alive && steady && median != null &&
                median <= activeMedian * betterRatio && activeMedian - median >= betterMinMs
            s.betterStreak = if (better) s.betterStreak + 1 else 0
            if (s.betterStreak >= betterRounds && (winner == null || median!! < rank(stats[winner]!!, fetch)!!)) winner = tag
        }
        return winner?.let {
            stats.values.forEach { s -> s.betterStreak = 0 }
            Decision.SwitchTo(it, Reason.BETTER)
        } ?: Decision.Keep
    }

    companion object {
        private const val HOUR_MS = 60 * 60_000L

        /** A server this much less reliable than the active one does not take its new connections. */
        const val FLAKIER_BY = 0.1

        private val BACKOFF_SECONDS = listOf(3L, 5L, 10L, 20L, 30L)

        /** Pause before reconnect attempt [attempt] (1-based): 3, 5, 10, 20, 30, then 30 s. */
        fun backoffSeconds(attempt: Int): Long = BACKOFF_SECONDS.getOrElse(attempt - 1) { BACKOFF_SECONDS.last() }
    }
}
