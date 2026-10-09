package app.rly3h.yakumo.data

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
}
