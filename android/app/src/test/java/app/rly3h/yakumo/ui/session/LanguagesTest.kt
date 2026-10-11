package app.rly3h.yakumo.ui.session

import app.rly3h.yakumo.data.listenPartner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
  private val en = languageByFlores("eng_Latn")
  private val ja = languageByFlores("jpn_Jpan")
  private val fr = languageByFlores("fra_Latn")

  @Test
  fun listenPartnersStayEnglishAndJapanese() {
    assertEquals(listOf(en), partnerOptions(ja))
    assertEquals(listOf(ja), partnerOptions(en))
  }

  @Test
  fun conversationPartnersListFiveLanguages() {
    assertEquals(
      listOf("eng_Latn", "fra_Latn", "deu_Latn", "ita_Latn", "zho_Hans"),
      conversationPartnerOptions(ja).map { it.flores },
    )
  }

  @Test
  fun offlineCapabilityNeedsBothSidesOffline() {
    assertTrue(conversationOfflineCapable(ja, en))
    assertFalse(conversationOfflineCapable(ja, fr))
    assertFalse(conversationOfflineCapable(ja, ja))
  }

  @Test
  fun offlineLanguagesAreEnglishAndJapanese() {
    assertEquals(listOf("eng_Latn", "jpn_Jpan"), OFFLINE_LANGUAGES.map { it.flores })
  }

  @Test
  fun liveLangCodes() {
    assertEquals("en", floresToLiveLang("eng_Latn"))
    assertEquals("ja", floresToLiveLang("jpn_Jpan"))
    assertEquals("fr", floresToLiveLang("fra_Latn"))
    assertEquals("de", floresToLiveLang("deu_Latn"))
    assertEquals("it", floresToLiveLang("ita_Latn"))
    assertEquals("zh", floresToLiveLang("zho_Hans"))
    assertEquals("en", floresToLiveLang("xxx"))
  }

  @Test
  fun geminiOverridesOnlySimplifiedChinese() {
    assertEquals("zh-Hans", floresToGeminiLang("zho_Hans"))
    for (code in listOf("eng_Latn", "jpn_Jpan", "fra_Latn", "deu_Latn", "ita_Latn", "xxx")) {
      assertEquals(floresToLiveLang(code), floresToGeminiLang(code))
    }
  }

  @Test
  fun unknownFloresFallsBackToEnglish() {
    assertEquals(en, languageByFlores("xxx"))
  }

  @Test
  fun listenPartnerFallsBackToAutoWhenNotUsable() {
    assertNull(listenPartner(null, ja))
    assertEquals(en, listenPartner("eng_Latn", ja))
    assertNull(listenPartner("fra_Latn", ja))
    assertNull(listenPartner("jpn_Jpan", ja))
  }
}
