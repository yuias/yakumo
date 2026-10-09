package app.rly3h.yakumo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelsIntegrityTest {
  private val sha = "a".repeat(64)

  @Test
  fun matchingSizeAndShaIsAccepted() {
    assertNull(integrityError("m", 10L, 10L, sha, sha))
  }

  @Test
  fun noExpectationsAreAccepted() {
    assertNull(integrityError("m", null, 123L, null, sha))
  }

  @Test
  fun sizeMismatchReportsBothNumbers() {
    val msg = integrityError("m", 10L, 7L, sha, sha)
    assertNotNull(msg)
    assertTrue(msg!!.contains("10") && msg.contains("7"))
  }

  @Test
  fun shaMismatchIsReported() {
    val msg = integrityError("m", 10L, 10L, sha, "b".repeat(64))
    assertNotNull(msg)
    assertTrue(msg!!.contains("sha256"))
  }

  @Test
  fun shaComparisonIgnoresCase() {
    assertNull(integrityError("m", null, 1L, sha.uppercase(), sha))
  }

  // Gradle runs unit tests with the module directory as the working directory.
  private fun manifest(): ModelManifest {
    val text = File("src/main/assets/models.json").readText()
    return Json { ignoreUnknownKeys = true }.decodeFromString(text)
  }

  @Test
  fun bundledManifestDecodes() {
    assertTrue(manifest().models.isNotEmpty())
  }

  @Test
  fun ggufAndVadFilesCarrySize() {
    val specs = manifest().models.filter { it.id == "vad" || it.id.startsWith("mt_") }
    assertTrue(specs.isNotEmpty())
    for (spec in specs) {
      assertTrue("${spec.id} has no files", spec.files.isNotEmpty())
      for (f in spec.files) assertNotNull("${spec.id}/${f.name} size", f.size)
    }
  }

  @Test
  fun noModelDirIsObsolete() {
    val m = manifest()
    val overlap = m.models.map { it.dir }.filter { it in m.obsoleteDirs }
    assertEquals(emptyList<String>(), overlap)
  }
}
