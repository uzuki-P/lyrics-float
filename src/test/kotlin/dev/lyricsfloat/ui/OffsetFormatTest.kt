package dev.lyricsfloat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OffsetFormatTest {
    @Test
    fun formatsMillisecondsAndSeconds() {
        assertEquals("0 ms", formatOffset(0))
        assertEquals("+250 ms", formatOffset(250))
        assertEquals("−950 ms", formatOffset(-950))
        assertEquals("+1.25 s", formatOffset(1_250))
        assertEquals("−12 s", formatOffset(-12_000))
    }

    @Test
    fun parsesTypedOffsets() {
        assertEquals(1_250, parseOffset("1250"))
        assertEquals(-1_500, parseOffset("-1.5s"))
        assertEquals(-250, parseOffset("−0,25 s"))
        assertEquals(300, parseOffset("300 ms"))
        assertNull(parseOffset("soon"))
        assertNull(parseOffset(" "))
    }
}
