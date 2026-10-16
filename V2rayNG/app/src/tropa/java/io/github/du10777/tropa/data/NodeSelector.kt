package io.github.du10777.tropa.data

/**
 * Decides which server new connections should use (pure logic, unit tested).
 *
 * - The active server is dead after [failThreshold] failed checks in a row: switch at once to
 *   the best server that answered its last check.
 * - Another server is "noticeably better" when its median latency is at most [betterRatio] of the
 *   active one and at least [betterMinMs] lower, [betterRounds] evaluations in a row.
 */
class NodeSelector(
    private var failThreshold: Int = CheckSettings.DEFAULTS.failThreshold,
    private var betterRatio: Double = CheckSettings.DEFAULTS.betterRatio,
    private var betterMinMs: Long = CheckSettings.DEFAULTS.betterMinMs.toLong(),
    private var betterRounds: Int = CheckSettings.DEFAULTS.betterRounds,
    private val window: Int = 5,
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

    class Stats {
        val latencies = ArrayDeque<Long>()
        var consecutiveFails = 0
        var checks = 0
        var betterStreak = 0

        val alive: Boolean get() = checks > 0 && consecutiveFails == 0
        val dead: Boolean get() = consecutiveFails > 0

        fun median(): Long? = latencies.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
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

    @Synchronized
    fun record(tag: String, ok: Boolean, latencyMs: Long?) {
        val s = stats.getOrPut(tag) { Stats() }
        s.checks++
        if (ok && latencyMs != null) {
            s.consecutiveFails = 0
            s.latencies.addLast(latencyMs)
            while (s.latencies.size > window) s.latencies.removeFirst()
        } else {
            s.consecutiveFails++
        }
    }

    @Synchronized
    fun isActiveDead(active: String): Boolean = (stats[active]?.consecutiveFails ?: 0) >= failThreshold

    /** Best server that answered its last check, lowest median latency first. */
    @Synchronized
    fun bestAlive(except: String? = null): String? =
        stats.filter { (tag, s) -> tag != except && s.alive }
            .minByOrNull { (_, s) -> s.median() ?: Long.MAX_VALUE }
            ?.key

    @Synchronized
    fun evaluate(active: String): Decision {
        if (isActiveDead(active)) {
            return bestAlive(except = active)?.let { Decision.SwitchTo(it, Reason.ACTIVE_DEAD) } ?: Decision.AllDead
        }
        val activeMedian = stats[active]?.median() ?: return Decision.Keep
        var winner: String? = null
        stats.forEach { (tag, s) ->
            if (tag == active) return@forEach
            val median = s.median()
            val better = s.alive && median != null &&
                median <= activeMedian * betterRatio && activeMedian - median >= betterMinMs
            s.betterStreak = if (better) s.betterStreak + 1 else 0
            if (s.betterStreak >= betterRounds && (winner == null || median!! < stats[winner]!!.median()!!)) winner = tag
        }
        return winner?.let {
            stats.values.forEach { s -> s.betterStreak = 0 }
            Decision.SwitchTo(it, Reason.BETTER)
        } ?: Decision.Keep
    }

    companion object {
        private val BACKOFF_SECONDS = listOf(3L, 5L, 10L, 20L, 30L)

        /** Pause before reconnect attempt [attempt] (1-based): 3, 5, 10, 20, 30, then 30 s. */
        fun backoffSeconds(attempt: Int): Long = BACKOFF_SECONDS.getOrElse(attempt - 1) { BACKOFF_SECONDS.last() }
    }
}
