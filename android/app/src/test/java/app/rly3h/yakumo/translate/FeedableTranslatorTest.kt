package app.rly3h.yakumo.translate

import org.junit.Assert.assertTrue
import org.junit.Test

class FeedableTranslatorTest {
  @Test
  fun openAiTranslatorAcceptsExternalAudio() {
    assertTrue(FeedableTranslator::class.java.isAssignableFrom(OpenAiTranslator::class.java))
  }

  @Test
  fun geminiTranslatorAcceptsExternalAudio() {
    assertTrue(FeedableTranslator::class.java.isAssignableFrom(GeminiTranslator::class.java))
  }
}
