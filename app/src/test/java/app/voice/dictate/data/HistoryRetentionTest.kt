package app.voice.dictate.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRetentionTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_000 * day
    private fun item(ageMs: Long) = HistoryItem(id = now - ageMs, text = "t", time = now - ageMs)
    private val items = listOf(item(0), item(day / 2), item(day), item(day + 1), item(10 * day), item(400 * day))

    @Test
    fun foreverKeepsEverything() {
        assertEquals(items, HistoryRepository.retain(items, HISTORY_FOREVER, now))
    }

    @Test
    fun neverKeepsNothing() {
        assertEquals(emptyList<HistoryItem>(), HistoryRepository.retain(items, HISTORY_NEVER, now))
    }

    @Test
    fun oneDayKeepsTheLast24HoursInclusive() {
        assertEquals(items.take(3), HistoryRepository.retain(items, 1, now))
    }

    @Test
    fun customDaysKeepNewerItems() {
        assertEquals(items.take(5), HistoryRepository.retain(items, 30, now))
        assertEquals(items, HistoryRepository.retain(items, HISTORY_MAX_DAYS, now))
    }

    @Test
    fun labels() {
        assertEquals("Forever", historyLabel(HISTORY_FOREVER))
        assertEquals("Off", historyLabel(HISTORY_NEVER))
        assertEquals("1 day", historyLabel(1))
        assertEquals("30 days", historyLabel(30))
    }

    @Test
    fun durations() {
        assertEquals("5 s", formatDuration(5))
        assertEquals("45 s", formatDuration(45))
        assertEquals("1 min", formatDuration(60))
        assertEquals("1 min 30 s", formatDuration(90))
        assertEquals("60 min", formatDuration(3600))
    }
}
