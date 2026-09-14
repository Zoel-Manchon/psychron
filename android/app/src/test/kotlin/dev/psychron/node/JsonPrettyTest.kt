package dev.psychron.node

import dev.psychron.node.JsonPretty.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPrettyTest {
    private val message = Contract.encode(
        Contract.Envelope("phone-01", "android-0.1.0", 4293404214L, 392, 1789405965L, 784014, 2000, 0),
        Contract.Summary(pressureHpa = 955.95, batteryTempC = 31.2, headingDeg = null),
    )!!

    @Test
    fun `nested groups are indented one level deeper than the envelope`() {
        assertEquals(
            """
            {
              "v": 2,
              "dev": "phone-01",
              "fw": "android-0.1.0",
              "boot": 4293404214,
              "seq": 392,
              "ts": 1789405965,
              "up": 784014,
              "win": 2000,
              "q": 0,
              "baro": {
                "hpa": 955.95
              },
              "batt": {
                "c": 31.2
              }
            }
            """.trimIndent(),
            JsonPretty.text(message),
        )
    }

    @Test
    fun `formatting changes whitespace and nothing else`() {
        val squeezed = JsonPretty.text(message).filterNot { it.isWhitespace() }
        assertEquals(message.filterNot { it.isWhitespace() }, squeezed)
    }

    @Test
    fun `keys and values are told apart`() {
        val segs = JsonPretty.segments(message)
        assertTrue(segs.any { it.text == "\"dev\"" && it.kind == Kind.KEY })
        assertTrue(segs.any { it.text == "\"phone-01\"" && it.kind == Kind.STRING })
        assertTrue(segs.any { it.text == "955.95" && it.kind == Kind.NUMBER })
    }

    @Test
    fun `a null clock is a literal, not a string`() {
        val json = Contract.encode(
            Contract.Envelope("phone-01", "0.1.0", 1, 1, null, 0, 2000, 0),
            Contract.Summary(batteryTempC = 30.0),
        )!!
        assertTrue(JsonPretty.segments(json).any { it.text == "null" && it.kind == Kind.LITERAL })
    }

    @Test
    fun `negative numbers keep their sign`() {
        val json = Contract.encode(
            Contract.Envelope("phone-01", "0.1.0", 1, 1, 1, 0, 2000, 0),
            Contract.Summary(soundRmsDbfs = -55.4, soundPeakDbfs = -37.6),
        )!!
        assertTrue(JsonPretty.segments(json).any { it.text == "-55.4" && it.kind == Kind.NUMBER })
    }
}
