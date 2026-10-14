package io.github.du10777.tropa.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionLogTest {

    @Test
    fun `trimming keeps the newest lines that fit`() {
        val lines = (1..10).map { "line-$it" } // 7-8 bytes each with the newline: 8 + 7 + 7 + 7 = 29
        val kept = ConnectionLog.keepNewest(lines, 30)
        assertEquals(listOf("line-7", "line-8", "line-9", "line-10"), kept)
    }

    @Test
    fun `everything fits when the limit is large`() {
        val lines = listOf("a", "b", "c")
        assertEquals(lines, ConnectionLog.keepNewest(lines, 1_000))
    }

    @Test
    fun `nothing is kept when even the newest line is too big`() {
        assertEquals(emptyList<String>(), ConnectionLog.keepNewest(listOf("0123456789"), 5))
    }

    @Test
    fun `each level writes its share of server checks`() {
        // ok, steady -> written?
        val cases = mapOf(
            LogLevel.ERRORS to listOf(false, false, true, false),
            LogLevel.NORMAL to listOf(true, false, true, false),
            LogLevel.VERBOSE to listOf(true, true, true, true),
            LogLevel.DEBUG to listOf(true, true, true, true),
        )
        cases.forEach { (level, expected) ->
            val actual = listOf(
                ConnectionLog.writesCheck(level, ok = true, steady = false),
                ConnectionLog.writesCheck(level, ok = true, steady = true),
                ConnectionLog.writesCheck(level, ok = false, steady = false),
                ConnectionLog.writesCheck(level, ok = false, steady = true),
            )
            assertEquals(expected, actual, level.name)
        }
    }
}
