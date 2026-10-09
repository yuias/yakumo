package app.rly3h.yakumo.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
data class ModelManifest(
  val models: List<ModelSpec>,
  // Directories under filesDir left behind by superseded models; Settings offers to delete them.
  val obsoleteDirs: List<String> = emptyList(),
)

@Serializable
data class ModelSpec(
  val id: String,
  val dir: String,
  val checkFile: String,
  val files: List<FileSpec> = emptyList(),
  val archive: ArchiveSpec? = null,
  // Human-readable name for Settings; falls back to id.
  val label: String? = null,
  // Rough download size shown before the model is fetched; null hides the hint.
  val approxMb: Int? = null,
) {
  val displayName: String get() = label ?: id
}

// size/sha256 are verified before the download is renamed into place; null skips that check.
@Serializable data class FileSpec(val name: String, val url: String, val size: Long? = null, val sha256: String? = null)

@Serializable data class ArchiveSpec(val url: String, val format: String, val size: Long? = null, val sha256: String? = null)

/**
 * Returns a human-readable error when a finished download does not match the manifest,
 * or null when it is acceptable. A null expectation is not checked; the sha256 comparison
 * ignores case.
 */
internal fun integrityError(
  name: String,
  expectedSize: Long?,
  actualSize: Long,
  expectedSha256: String?,
  actualSha256: String,
): String? {
  if (expectedSize != null && expectedSize != actualSize) {
    return "$name: size mismatch (expected $expectedSize bytes, got $actualSize)"
  }
  if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
    return "$name: sha256 mismatch (expected $expectedSha256, got $actualSha256)"
  }
  return null
}

/** Thrown by [Models.ensure] when a `cancel` callback returns true mid-transfer. */
class ModelCancelled : Exception("download cancelled")

/**
 * First-run model provisioning: reads assets/models.json and downloads each
 * model into internal storage (filesDir). Replaces the old adb-push workflow.
 * Internal storage is required because the NDK's raw open() is denied on
 * external Android/data on some OEMs (see docs/architecture.md §6).
 */
object Models {
  private val json = Json { ignoreUnknownKeys = true }

  fun manifest(context: Context): ModelManifest {
    val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
    return json.decodeFromString(text)
  }

  fun spec(context: Context, id: String): ModelSpec =
    manifest(context).models.first { it.id == id }

  fun dir(context: Context, id: String): File =
    File(context.filesDir, spec(context, id).dir)

  fun isPresent(context: Context, id: String): Boolean {
    val s = spec(context, id)
    return File(File(context.filesDir, s.dir), s.checkFile).exists()
  }

  /**
   * Bytes currently on disk under the model's directory, including incomplete
   * files-mode downloads (`*.part`, or some files missing). 0 when the directory
   * does not exist.
   */
  fun sizeOnDisk(context: Context, id: String): Long {
    val dir = dir(context, id)
    if (!dir.exists()) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
  }

  /**
   * Removes the model and any download leftovers so the next [ensure] fetches it
   * again. Blocking — call from an IO thread. The caller must ensure nothing is
   * downloading or using the model; a model already loaded in native memory keeps
   * working until process restart.
   */
  fun delete(context: Context, id: String) {
    val s = spec(context, id)
    File(context.filesDir, s.dir).deleteRecursively()
    File(context.filesDir, ".staging-$id").deleteRecursively()
    // Archive mode downloads to "$id-archive" and download() writes ".part" beside it.
    File(context.cacheDir, "$id-archive").delete()
    File(context.cacheDir, "$id-archive.part").delete()
  }

  private fun obsoleteDirFiles(context: Context): List<File> =
    manifest(context).obsoleteDirs.map { File(context.filesDir, it) }.filter { it.exists() }

  /** Bytes used by directories of superseded models listed in `obsoleteDirs`. */
  fun obsoleteBytes(context: Context): Long =
    obsoleteDirFiles(context).sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

  /** Deletes the `obsoleteDirs`. Blocking — call from an IO thread. */
  fun deleteObsolete(context: Context) {
    obsoleteDirFiles(context).forEach { it.deleteRecursively() }
  }

  /**
   * Downloads/extracts the model if missing. Blocking — call from an IO thread.
   * `cancel` is polled during transfer/extraction; returning true aborts with
   * [ModelCancelled] and removes the partial download.
   */
  fun ensure(
    context: Context,
    id: String,
    onProgress: (String) -> Unit = {},
    // Fraction in 0..1 for a determinate progress bar; null means "indeterminate"
    // (size unknown — e.g. during bz2 extraction or when the server omits a length).
    onFraction: (Float?) -> Unit = {},
    cancel: () -> Boolean = { false },
  ) {
    val s = spec(context, id)
    val target = File(File(context.filesDir, s.dir), s.checkFile)
    if (target.exists()) {
      onProgress("$id: present")
      return
    }
    val archive = s.archive
    if (archive != null) {
      // tar.bz2 contains a top-level folder == s.dir. Extract into a staging dir
      // first and only swap it into place once complete, so a cancelled/failed
      // extraction never leaves a half-written model that looks "present".
      val tmp = File(context.cacheDir, "$id-archive")
      val staging = File(context.filesDir, ".staging-$id")
      onProgress("$id: downloading…")
      // Verified before extraction so a corrupt archive never reaches the staging dir.
      download(archive.url, tmp, cancel, id, archive.size, archive.sha256) { written, total ->
        onProgress("$id: ${written / 1_000_000} MB")
        onFraction(if (total > 0) written.toFloat() / total else null)
      }
      onProgress("$id: extracting…")
      onFraction(null) // uncompressed size isn't known up front → indeterminate
      try {
        staging.deleteRecursively()
        staging.mkdirs()
        extractTarBz2(tmp, staging, cancel) { b -> onProgress("$id: extracting… ${b / 1_000_000} MB") }
        val extracted = File(staging, s.dir)
        check(extracted.isDirectory) { "archive missing top-level dir ${s.dir}" }
        val finalDir = File(context.filesDir, s.dir)
        finalDir.deleteRecursively()
        check(extracted.renameTo(finalDir)) { "failed to move ${s.dir} into place" }
      } finally {
        staging.deleteRecursively()
        tmp.delete()
      }
    } else {
      val dir = File(context.filesDir, s.dir).apply { mkdirs() }
      for (f in s.files) {
        // Skip files already on disk so adding one new file (e.g. a swapped
        // decoder) doesn't re-download the unchanged ones.
        if (File(dir, f.name).exists()) {
          onProgress("$id: ${f.name} present")
          continue
        }
        onProgress("$id: ${f.name}…")
        download(f.url, File(dir, f.name), cancel, "$id/${f.name}", f.size, f.sha256) { written, total ->
          onProgress("$id: ${f.name} ${written / 1_000_000} MB")
          onFraction(if (total > 0) written.toFloat() / total else null)
        }
      }
    }
    onProgress("$id: done")
  }

  // Streaming download that manually follows redirects (HF/GitHub CDN hops).
  // onProgress reports (bytesWritten, contentLength); contentLength is -1 when unknown.
  // The SHA-256 is computed while writing; the .part file is renamed to `dest` only when
  // size/sha256 match, so a file that exists under its final name is always verified.
  private fun download(
    urlStr: String,
    dest: File,
    cancel: () -> Boolean,
    name: String,
    expectedSize: Long?,
    expectedSha256: String?,
    onProgress: (Long, Long) -> Unit,
  ) {
    var url = urlStr
    var hops = 0
    while (true) {
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 30_000
        readTimeout = 60_000
      }
      val code = conn.responseCode
      if (code in 300..399) {
        val loc = conn.getHeaderField("Location") ?: error("redirect without Location")
        conn.disconnect()
        url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
        if (++hops > 8) error("too many redirects")
        continue
      }
      if (code != 200) {
        conn.disconnect()
        error("HTTP $code for $url")
      }
      val contentLength = conn.contentLengthLong // -1 when the server omits it
      val part = File(dest.parentFile, dest.name + ".part")
      var cancelled = false
      var total = 0L
      val digest = MessageDigest.getInstance("SHA-256")
      conn.inputStream.use { input ->
        part.outputStream().use { out ->
          val buf = ByteArray(1 shl 16)
          var written = 0L
          while (true) {
            if (cancel()) {
              cancelled = true
              break
            }
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            digest.update(buf, 0, n)
            written += n
            onProgress(written, contentLength)
          }
          total = written
        }
      }
      conn.disconnect()
      if (cancelled) {
        part.delete()
        throw ModelCancelled()
      }
      val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
      integrityError(name, expectedSize, total, expectedSha256, actualSha256)?.let { msg ->
        part.delete()
        throw IllegalStateException(msg)
      }
      if (dest.exists()) dest.delete()
      check(part.renameTo(dest)) { "rename ${part.name} -> ${dest.name} failed" }
      return
    }
  }

  private fun extractTarBz2(
    archive: File,
    destDir: File,
    cancel: () -> Boolean,
    onProgress: (Long) -> Unit,
  ) {
    val destRoot = destDir.canonicalFile
    var written = 0L
    archive.inputStream().buffered().use { fin ->
      BZip2CompressorInputStream(fin).use { bz ->
        TarArchiveInputStream(bz).use { tar ->
          var entry = tar.nextEntry
          while (entry != null) {
            if (cancel()) throw ModelCancelled()
            val out = File(destDir, entry.name).canonicalFile
            require(out.path.startsWith(destRoot.path)) { "tar entry escapes dest: ${entry.name}" }
            if (entry.isDirectory) {
              out.mkdirs()
            } else {
              out.parentFile?.mkdirs()
              out.outputStream().use { os ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                  if (cancel()) throw ModelCancelled()
                  val n = tar.read(buf)
                  if (n < 0) break
                  os.write(buf, 0, n)
                  written += n
                  onProgress(written)
                }
              }
            }
            entry = tar.nextEntry
          }
        }
      }
    }
  }
}
