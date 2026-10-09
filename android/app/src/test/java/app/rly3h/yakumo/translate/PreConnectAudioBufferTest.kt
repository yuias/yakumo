package app.rly3h.yakumo.translate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreConnectAudioBufferTest {
  private fun chunk(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

  @Test
  fun drainReturnsChunksInOfferOrder() {
    val buf = PreConnectAudioBuffer(100)
    buf.offer(chunk(10, 1))
    buf.offer(chunk(10, 2))
    buf.offer(chunk(10, 3))
    val out = buf.drain()
    assertEquals(3, out.size)
    assertArrayEquals(chunk(10, 1), out[0])
    assertArrayEquals(chunk(10, 2), out[1])
    assertArrayEquals(chunk(10, 3), out[2])
  }

  @Test
  fun drainEmptiesTheBuffer() {
    val buf = PreConnectAudioBuffer(100)
    buf.offer(chunk(10, 1))
    assertEquals(10, buf.size())
    buf.drain()
    assertEquals(0, buf.size())
    assertTrue(buf.drain().isEmpty())
  }

  @Test
  fun exceedingMaxBytesDropsOldestWholeChunks() {
    val buf = PreConnectAudioBuffer(25)
    buf.offer(chunk(10, 1))
    buf.offer(chunk(10, 2))
    buf.offer(chunk(10, 3)) // 30 > 25: the first chunk goes
    assertEquals(20, buf.size())
    val out = buf.drain()
    assertEquals(2, out.size)
    assertArrayEquals(chunk(10, 2), out[0])
    assertArrayEquals(chunk(10, 3), out[1])
  }

  @Test
  fun sizeStaysWithinMaxBytesAcrossManyOffers() {
    val buf = PreConnectAudioBuffer(64)
    repeat(50) { buf.offer(chunk(16, it)) }
    assertTrue(buf.size() <= 64)
    assertEquals(64, buf.size())
    assertArrayEquals(chunk(16, 49), buf.drain().last())
  }

  @Test
  fun singleChunkLargerThanMaxBytesIsKeptAlone() {
    val buf = PreConnectAudioBuffer(10)
    buf.offer(chunk(4, 1))
    buf.offer(chunk(30, 2))
    assertEquals(30, buf.size())
    val out = buf.drain()
    assertEquals(1, out.size)
    assertArrayEquals(chunk(30, 2), out[0])
  }
}
