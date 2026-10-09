package app.rly3h.yakumo.translate

/** How much mic audio the online engines keep while the socket is still connecting. */
internal const val PRE_CONNECT_MS = 2000

/**
 * Bounded FIFO of PCM chunks captured before the socket is ready; drops the oldest
 * whole chunks beyond [maxBytes]. A single chunk larger than [maxBytes] is kept
 * alone rather than dropped, so a small cap can never leave the buffer empty
 * while audio keeps arriving.
 *
 * Capture runs on its own thread while the engine may drain from another, so every
 * method is synchronized.
 */
internal class PreConnectAudioBuffer(private val maxBytes: Int) {
  private val chunks = ArrayDeque<ByteArray>()
  private var bytes = 0

  @Synchronized
  fun offer(chunk: ByteArray) {
    chunks.addLast(chunk)
    bytes += chunk.size
    // Keep the newest chunk even if it alone exceeds the cap (size > 1 guard).
    while (bytes > maxBytes && chunks.size > 1) {
      bytes -= chunks.removeFirst().size
    }
  }

  /** Returns the buffered chunks in capture order and empties the buffer. */
  @Synchronized
  fun drain(): List<ByteArray> {
    val out = chunks.toList()
    chunks.clear()
    bytes = 0
    return out
  }

  /** Buffered bytes. */
  @Synchronized
  fun size(): Int = bytes
}
