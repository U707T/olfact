package cn.olfact.download

import cn.olfact.download.m3u8.consumeSample
import org.junit.Assert.*
import org.junit.Test

class SampleDataTest {
    @Test fun `sample offsets preserve bytes already belonging to the next frame`() {
        val buffer = "ABCDEF".toByteArray()
        var first = ""
        val pending = consumeSample(buffer, 6, 3, 3) { data ->
            first = ByteArray(data.remaining()).also { data.get(it) }.toString(Charsets.UTF_8)
        }
        assertEquals("ABC", first)
        assertEquals(3, pending)
        var second = ""
        assertEquals(0, consumeSample(buffer, pending, 3, 0) { data ->
            second = ByteArray(data.remaining()).also { data.get(it) }.toString(Charsets.UTF_8)
        })
        assertEquals("DEF", second)
    }

    @Test fun `discarded leading bytes are not included in the sample`() {
        val buffer = "JUNKFRAMEtail".toByteArray()
        var actual = ""
        consumeSample(buffer, buffer.size, 5, 4) { data ->
            actual = ByteArray(data.remaining()).also { data.get(it) }.toString(Charsets.UTF_8)
        }
        assertEquals("FRAME", actual)
        assertEquals("tail", buffer.copyOfRange(0, 4).toString(Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid sample bounds fail rather than writing arbitrary bytes`() {
        consumeSample(ByteArray(8), 2, 3, 1) {}
    }
}
