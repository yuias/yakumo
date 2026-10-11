package app.rly3h.yakumo.data

import app.rly3h.yakumo.ui.session.languageByFlores
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLogicTest {
  @Test
  fun noStoredModeAndNoLegacyIsSegmented() {
    assertEquals(AsrMode.SEGMENTED, migrateAsrMode(null, null))
  }

  @Test
  fun legacyStreamingTrueMigratesToStreaming() {
    assertEquals(AsrMode.STREAMING, migrateAsrMode(null, true))
  }

  @Test
  fun legacyStreamingFalseMigratesToSegmented() {
    assertEquals(AsrMode.SEGMENTED, migrateAsrMode(null, false))
  }

  @Test
  fun storedModeWinsOverLegacy() {
    assertEquals(AsrMode.STREAMING, migrateAsrMode("STREAMING", false))
    assertEquals(AsrMode.SEGMENTED, migrateAsrMode("SEGMENTED", true))
  }

  @Test
  fun unknownStoredModeFallsBackToLegacy() {
    assertEquals(AsrMode.STREAMING, migrateAsrMode("bogus", true))
    assertEquals(AsrMode.SEGMENTED, migrateAsrMode("bogus", null))
  }

  @Test
  fun requiredModelsForAllCombinations() {
    assertEquals(listOf("vad", "asr", "mt_lfm2"), requiredModelIds(AsrMode.SEGMENTED, MtModel.LFM2))
    assertEquals(listOf("vad", "asr", "mt_hymt2"), requiredModelIds(AsrMode.SEGMENTED, MtModel.HYMT2))
    assertEquals(listOf("asr_stream", "mt_lfm2"), requiredModelIds(AsrMode.STREAMING, MtModel.LFM2))
    assertEquals(listOf("asr_stream", "mt_hymt2"), requiredModelIds(AsrMode.STREAMING, MtModel.HYMT2))
  }

  @Test
  fun missingOrUnknownSessionModeIsListen() {
    assertEquals(SessionMode.LISTEN, parseSessionMode(null))
    assertEquals(SessionMode.LISTEN, parseSessionMode("bogus"))
  }

  @Test
  fun storedConversationModeIsKept() {
    assertEquals(SessionMode.CONVERSATION, parseSessionMode("CONVERSATION"))
    assertEquals(SessionMode.LISTEN, parseSessionMode("LISTEN"))
  }

  @Test
  fun conversationPartnerDefaultsToFirstOtherLanguage() {
    val en = languageByFlores("eng_Latn")
    val ja = languageByFlores("jpn_Jpan")
    assertEquals(en, resolveConversationPartner(null, ja))
    assertEquals(ja, resolveConversationPartner(null, en))
  }

  @Test
  fun storedConversationPartnerIsUsedWhenValid() {
    val ja = languageByFlores("jpn_Jpan")
    assertEquals(languageByFlores("fra_Latn"), resolveConversationPartner("fra_Latn", ja))
  }

  @Test
  fun conversationPartnerEqualToMineOrUnknownFallsBack() {
    val en = languageByFlores("eng_Latn")
    val ja = languageByFlores("jpn_Jpan")
    assertEquals(en, resolveConversationPartner("jpn_Jpan", ja))
    assertEquals(en, resolveConversationPartner("xxx", ja))
    // Unknown code with English as mine must not resolve to English itself.
    assertEquals(ja, resolveConversationPartner("xxx", en))
  }
}
