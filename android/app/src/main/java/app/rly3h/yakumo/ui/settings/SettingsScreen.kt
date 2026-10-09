package app.rly3h.yakumo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Row
import app.rly3h.yakumo.BuildConfig
import app.rly3h.yakumo.R
import app.rly3h.yakumo.RecordingService
import app.rly3h.yakumo.translate.GeminiLive
import app.rly3h.yakumo.translate.OpenAiRealtime
import app.rly3h.yakumo.data.AsrMode
import app.rly3h.yakumo.data.ModelCancelled
import app.rly3h.yakumo.data.ModelSpec
import app.rly3h.yakumo.data.Models
import app.rly3h.yakumo.data.MtModel
import app.rly3h.yakumo.data.OnlineProvider
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.data.requiredModelIds
import app.rly3h.yakumo.translate.modelFile
import app.rly3h.yakumo.translate.releaseUnusedEngines
import app.rly3h.yakumo.translate.unloadEngineFor
import app.rly3h.yakumo.ui.session.EndpointBounds
import app.rly3h.yakumo.ui.session.LANGUAGES
import app.rly3h.yakumo.ui.session.OnlineBounds
import app.rly3h.yakumo.ui.session.VadBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.asrLoad
import uniffi.translatecore.asrStreamLoad
import uniffi.translatecore.coreVersion
import uniffi.translatecore.mtLoad
import uniffi.translatecore.mtSupported
import uniffi.translatecore.mtSystemInfo
import uniffi.translatecore.mtUnload
import uniffi.translatecore.sherpaVersion
import uniffi.translatecore.vadLoad

private val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private const val ENGINE_IDLE = "Not loaded. The first session loads them."

private data class ModelState(val present: Boolean, val bytes: Long)

// CPU capability and ggml's feature line, queried once off the main thread.
private data class MtInfo(val supported: Boolean, val systemInfo: String)

// Same MB convention as the download overlay (bytes / 1_000_000).
private fun formatMb(bytes: Long): String =
  if (bytes < 1_000_000) "<1 MB" else "${bytes / 1_000_000} MB"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = remember { Settings(context) }
  val specs = remember { Models.manifest(context).models }
  val modelStates = remember { mutableStateMapOf<String, ModelState>() }

  fun refresh(id: String) {
    modelStates[id] = ModelState(Models.isPresent(context, id), Models.sizeOnDisk(context, id))
  }

  fun refreshAll() = specs.forEach { refresh(it.id) }
  remember { refreshAll() }

  var modelMsg by remember { mutableStateOf<String?>(null) }
  var pendingDelete by remember { mutableStateOf<String?>(null) }
  var deleting by remember { mutableStateOf(false) }
  // Not Compose state: read fresh on every composition.
  val recording = RecordingService.onStopRequested != null

  var myLang by remember { mutableStateOf(settings.myLangFlores) }
  var rate by remember { mutableStateOf(settings.speechRate) }
  var autoSpeak by remember { mutableStateOf(settings.autoSpeak) }
  var asrMode by remember { mutableStateOf(settings.asrMode) }
  var mtModel by remember { mutableStateOf(settings.mtModel) }
  var engineStatus by remember { mutableStateOf(ENGINE_IDLE) }
  var busy by remember { mutableStateOf(false) }

  // Download overlay state. The cancel flag is read from the IO thread, so it is
  // an AtomicBoolean rather than Compose state.
  var downloading by remember { mutableStateOf(false) }
  var dlProgress by remember { mutableStateOf("") }
  // null => indeterminate bar (size unknown / between files); else 0..1.
  var dlFraction by remember { mutableStateOf<Float?>(null) }
  val cancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

  fun downloadModels(targetIds: List<String>) {
    downloading = true
    cancelFlag.set(false)
    dlProgress = "Starting…"
    dlFraction = null
    scope.launch {
      try {
        withContext(Dispatchers.IO) {
          for (id in targetIds) {
            Models.ensure(
              context,
              id,
              onProgress = { dlProgress = it },
              onFraction = { dlFraction = it },
              cancel = { cancelFlag.get() },
            )
          }
        }
        modelMsg = null
      } catch (c: ModelCancelled) {
        modelMsg = "Download cancelled."
      } catch (e: Throwable) {
        modelMsg = "Download failed: ${e.message}"
      } finally {
        // Also on cancel/failure, so finished models in a batch still show up.
        refreshAll()
        downloading = false
      }
    }
  }

  val idle = !busy && !downloading && !deleting

  // mtSystemInfo() initializes the llama backend, so it must not run on the main thread.
  val mtInfo by produceState<MtInfo?>(null) {
    value = withContext(Dispatchers.IO) { MtInfo(mtSupported(), mtSystemInfo()) }
  }

  var obsoleteBytes by remember { mutableStateOf(0L) }
  LaunchedEffect(Unit) { obsoleteBytes = withContext(Dispatchers.IO) { Models.obsoleteBytes(context) } }

  // Persist first, then free the engines the new choice no longer uses.
  fun selectAsrMode(mode: AsrMode) {
    if (mode == asrMode) return
    asrMode = mode
    settings.asrMode = mode
    scope.launch {
      try {
        withContext(Dispatchers.IO) { releaseUnusedEngines(mode) }
        engineStatus = ENGINE_IDLE
      } catch (e: Throwable) {
        engineStatus = "Unload failed: ${e.message}"
      }
    }
  }

  fun selectMtModel(model: MtModel) {
    if (model == mtModel) return
    mtModel = model
    settings.mtModel = model
    scope.launch {
      try {
        withContext(Dispatchers.IO) { mtUnload() }
        engineStatus = ENGINE_IDLE
      } catch (e: Throwable) {
        engineStatus = "Unload failed: ${e.message}"
      }
    }
  }

  fun statusText(spec: ModelSpec): String {
    val st = modelStates[spec.id] ?: return ""
    return when {
      st.present -> "Downloaded · ${formatMb(st.bytes)}"
      st.bytes > 0 -> "Incomplete · ${formatMb(st.bytes)}"
      else -> "Not downloaded" + (spec.approxMb?.let { " (~$it MB)" } ?: "")
    }
  }

  fun downloadEnabled(id: String) = idle && modelStates[id]?.present == false

  // Enabled on bytes > 0, not only present, so leftovers of a failed
  // download can be cleared too.
  fun deleteEnabled(id: String) = idle && !recording && (modelStates[id]?.bytes ?: 0L) > 0

  var epRule1 by remember { mutableStateOf(settings.endpointRule1) }
  var epRule2 by remember { mutableStateOf(settings.endpointRule2) }
  var epRule3 by remember { mutableStateOf(settings.endpointRule3) }

  var vadThresh by remember { mutableStateOf(settings.vadThreshold) }
  var vadSilence by remember { mutableStateOf(settings.vadMinSilenceMs.toFloat()) }
  var vadMinSpeech by remember { mutableStateOf(settings.vadMinSpeechMs.toFloat()) }
  var vadMaxSpeech by remember { mutableStateOf(settings.vadMaxSpeechMs.toFloat()) }

  // Online — provider-scoped. The key field is never pre-filled with the stored
  // secret, and resets when the provider changes (each provider has its own key).
  var provider by remember { mutableStateOf(settings.onlineProvider) }
  var apiKeyInput by remember { mutableStateOf("") }
  var keyVisible by remember { mutableStateOf(false) }
  var onlineStatus by remember { mutableStateOf(if (settings.hasApiKey(provider)) "Key saved." else "No key set.") }
  var idleGap by remember { mutableStateOf(settings.onlineIdleGapMs.toFloat()) }

  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SectionTitle("Your language")
    Text(
      "The language you speak. The other person's language is chosen on the New " +
        "Session screen (auto-detected by default).",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      LANGUAGES.forEach { opt ->
        FilterChip(
          selected = myLang == opt.flores,
          onClick = {
            myLang = opt.flores
            settings.myLangFlores = opt.flores
          },
          label = { Text(opt.label) },
        )
      }
    }

    HorizontalDivider()

    SectionTitle("Playback")
    Text("Speech rate", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      RATES.forEach { r ->
        FilterChip(
          selected = rate == r,
          onClick = {
            rate = r
            settings.speechRate = r
          },
          label = { Text("${r}x") },
        )
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("Speak translations aloud", style = MaterialTheme.typography.bodyMedium)
      Switch(
        checked = autoSpeak,
        onCheckedChange = {
          autoSpeak = it
          settings.autoSpeak = it
        },
      )
    }

    HorizontalDivider()

    SectionTitle("Speech recognition")
    ChoiceRow(
      selected = asrMode == AsrMode.SEGMENTED,
      enabled = !recording && !busy,
      title = "Segmented",
      description = "SenseVoice. Best accuracy; detects English/Japanese per utterance. " +
        "Turns are split by voice detection.",
      onSelect = { selectAsrMode(AsrMode.SEGMENTED) },
    )
    ChoiceRow(
      selected = asrMode == AsrMode.STREAMING,
      enabled = !recording && !busy,
      title = "Streaming",
      description = "Nemotron 3.5. Live partial transcript while you speak; Japanese accuracy " +
        "is lower than SenseVoice. Turns are split by the end-of-turn rules below.",
      onSelect = { selectAsrMode(AsrMode.STREAMING) },
    )
    if (asrMode == AsrMode.STREAMING) {
      Text(
        "End of turn — how the streaming recognizer splits utterances. Applies to the next session; changing these reloads the model.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
      VadSlider("Silence to end a turn", epRule2, EndpointBounds.rule2, { "%.1f s".format(it) }, { epRule2 = it }) {
        settings.endpointRule2 = epRule2
      }
      VadSlider("Silence before speech", epRule1, EndpointBounds.rule1, { "%.1f s".format(it) }, { epRule1 = it }) {
        settings.endpointRule1 = epRule1
      }
      VadSlider("Max utterance length", epRule3, EndpointBounds.rule3, { "${it.toInt()} s" }, { epRule3 = it }) {
        settings.endpointRule3 = epRule3
      }
      TextButton(onClick = {
        settings.resetEndpoint()
        epRule1 = settings.endpointRule1
        epRule2 = settings.endpointRule2
        epRule3 = settings.endpointRule3
      }) { Text("Reset to defaults") }
    }

    HorizontalDivider()

    SectionTitle("Translation")
    ChoiceRow(
      selected = mtModel == MtModel.LFM2,
      enabled = !recording && !busy,
      title = "LFM2",
      description = "LFM2-350M-ENJP-MT. English↔Japanese only; small and fast (~379 MB).",
      onSelect = { selectMtModel(MtModel.LFM2) },
    )
    ChoiceRow(
      selected = mtModel == MtModel.HYMT2,
      enabled = !recording && !busy,
      title = "Hy-MT2",
      description = "Hy-MT2-1.8B. Multilingual; larger and slower (~1.1 GB, needs more memory).",
      onSelect = { selectMtModel(MtModel.HYMT2) },
    )
    if (mtInfo?.supported == false) {
      Text(
        "This device's CPU lacks the dot-product instructions the offline translator needs. " +
          "Offline translation is unavailable; Online mode still works.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    if (recording) {
      Text(
        "Speech recognition and translation can't be changed while a session is recording.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }

    HorizontalDivider()

    SectionTitle("Models")
    Text(
      "Required for offline translation. Downloaded once and stored on this device.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    val requiredIds = requiredModelIds(asrMode, mtModel)
    val missingRequired = requiredIds.filter { modelStates[it]?.present != true }
    Button(
      enabled = idle && missingRequired.isNotEmpty(),
      onClick = { downloadModels(missingRequired) },
    ) { Text("Download models for current settings") }
    if (missingRequired.isEmpty()) {
      Text(
        "All models for the current settings are downloaded.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    specs.forEach { spec ->
      ModelRow(
        label = spec.displayName + if (spec.id in requiredIds) " · in use" else "",
        status = statusText(spec),
        downloadEnabled = downloadEnabled(spec.id),
        deleteEnabled = deleteEnabled(spec.id),
        onDownload = { downloadModels(listOf(spec.id)) },
        onDelete = { pendingDelete = spec.id },
      )
    }
    if (obsoleteBytes > 0) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "Old model files from earlier versions · ${formatMb(obsoleteBytes)}",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          enabled = idle && !recording,
          onClick = {
            deleting = true
            scope.launch {
              try {
                withContext(Dispatchers.IO) { Models.deleteObsolete(context) }
                modelMsg = "Removed old model files."
              } catch (e: Throwable) {
                modelMsg = "Remove failed: ${e.message}"
              } finally {
                obsoleteBytes = withContext(Dispatchers.IO) { Models.obsoleteBytes(context) }
                deleting = false
              }
            }
          },
        ) { Text("Remove") }
      }
    }
    if (recording) {
      Text(
        "Model deletion is unavailable while a session is recording.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    modelMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    val preloadReady = missingRequired.isEmpty() && mtInfo?.supported == true
    Button(
      enabled = idle && preloadReady,
      onClick = {
        busy = true
        engineStatus = "Loading…"
        // Snapshot: the radios are disabled while busy, but keep both paths consistent.
        val mode = asrMode
        val mt = mtModel
        scope.launch {
          try {
            withContext(Dispatchers.IO) {
              engineStatus = "Loading speech recognition…"
              when (mode) {
                AsrMode.SEGMENTED -> {
                  val vad = settings.vadParams()
                  vadLoad(
                    Models.dir(context, "vad").absolutePath,
                    vad.threshold,
                    vad.minSilenceMs / 1000f,
                    vad.minSpeechMs / 1000f,
                    vad.maxSpeechMs / 1000f,
                  )
                  asrLoad(Models.dir(context, "asr").absolutePath)
                }
                AsrMode.STREAMING -> {
                  val ep = settings.endpointParams()
                  asrStreamLoad(Models.dir(context, "asr_stream").absolutePath, ep.rule1, ep.rule2, ep.rule3, "auto")
                }
              }
              engineStatus = "Loading translation…"
              mtLoad(modelFile(context, mt.modelId).absolutePath)
            }
            engineStatus = "Loaded and ready."
          } catch (e: Throwable) {
            engineStatus = "Load failed: ${e.message}"
          } finally {
            busy = false
          }
        }
      },
    ) { Text("Preload models (faster first session)") }
    Text(
      "Loads speech recognition and translation into memory now instead of at the start of the first session.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    if (!preloadReady) {
      Text(
        when {
          missingRequired.isNotEmpty() -> "Download the models first."
          mtInfo == null -> "Checking device support…"
          else -> "Offline translation is unavailable on this device."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    Text(engineStatus, style = MaterialTheme.typography.bodySmall)

    HorizontalDivider()

    if (asrMode == AsrMode.SEGMENTED) {
      SectionTitle("Voice detection")
      Text(
        "Silero VAD splits speech into turns while recording. Robust to background " +
          "noise. Applies to the next session; changing these reloads the detector.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
      VadSlider("Silence to split a turn", vadSilence, VadBounds.minSilenceMs, { "${it.toInt()} ms" }, { vadSilence = it }) {
        settings.vadMinSilenceMs = vadSilence.toInt()
      }
      VadSlider("Speech sensitivity (lower = more sensitive)", vadThresh, VadBounds.threshold, { "%.2f".format(it) }, { vadThresh = it }) {
        settings.vadThreshold = vadThresh
      }
      VadSlider("Min speech length", vadMinSpeech, VadBounds.minSpeechMs, { "${it.toInt()} ms" }, { vadMinSpeech = it }) {
        settings.vadMinSpeechMs = vadMinSpeech.toInt()
      }
      VadSlider("Max segment length", vadMaxSpeech, VadBounds.maxSpeechMs, { "${(it / 1000).toInt()} s" }, { vadMaxSpeech = it }) {
        settings.vadMaxSpeechMs = vadMaxSpeech.toInt()
      }
      TextButton(onClick = {
        settings.resetVad()
        vadThresh = settings.vadThreshold
        vadSilence = settings.vadMinSilenceMs.toFloat()
        vadMinSpeech = settings.vadMinSpeechMs.toFloat()
        vadMaxSpeech = settings.vadMaxSpeechMs.toFloat()
      }) { Text("Reset to defaults") }

      HorizontalDivider()
    }

    SectionTitle("Online translation")
    val providerLabel = when (provider) {
      OnlineProvider.OPENAI -> "OpenAI"
      OnlineProvider.GEMINI -> "Gemini"
    }
    Text(
      "Optional. When online, translation runs on a cloud model for low-latency " +
        "speech-to-speech. Audio is sent to the provider and billed to your key. " +
        "Offline stays the default — toggle Online next to the mic. Each provider " +
        "stores its own key, encrypted on-device.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    // Provider chooser — the engine used when Online is toggled on. Switching here
    // swaps which provider's key the field below edits.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OnlineProvider.entries.forEach { p ->
        val label = when (p) {
          OnlineProvider.OPENAI -> "OpenAI Realtime"
          OnlineProvider.GEMINI -> "Gemini Live"
        }
        FilterChip(
          selected = provider == p,
          onClick = {
            provider = p
            settings.onlineProvider = p
            apiKeyInput = ""
            keyVisible = false
            onlineStatus = if (settings.hasApiKey(p)) "Key saved." else "No key set."
          },
          label = { Text(label) },
        )
      }
    }
    OutlinedTextField(
      value = apiKeyInput,
      onValueChange = { apiKeyInput = it },
      label = { Text("$providerLabel API key") },
      singleLine = true,
      visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        TextButton(onClick = { keyVisible = !keyVisible }) { Text(if (keyVisible) "Hide" else "Show") }
      },
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        enabled = apiKeyInput.isNotBlank() && !busy,
        onClick = {
          settings.setApiKey(context, provider, apiKeyInput.trim())
          apiKeyInput = ""
          keyVisible = false
          onlineStatus = "Key saved."
        },
      ) { Text("Save key") }
      TextButton(
        enabled = !busy,
        onClick = {
          settings.clearApiKey(provider)
          onlineStatus = "Key cleared."
        },
      ) { Text("Clear key") }
      TextButton(
        enabled = settings.hasApiKey(provider) && !busy,
        onClick = {
          busy = true
          onlineStatus = "Testing…"
          val testProvider = provider
          scope.launch {
            onlineStatus = try {
              withContext(Dispatchers.IO) {
                val key = settings.apiKey(context, testProvider) ?: error("Key could not be read")
                // Succeeds = key + network OK.
                when (testProvider) {
                  OnlineProvider.OPENAI -> OpenAiRealtime.mintEphemeral(key)
                  OnlineProvider.GEMINI -> GeminiLive.testKey(key)
                }
              }
              "Connection OK."
            } catch (e: Throwable) {
              "Test failed: ${e.message}"
            } finally {
              busy = false
            }
          }
        },
      ) { Text("Test connection") }
    }
    Text(onlineStatus, style = MaterialTheme.typography.bodySmall)

    Text(
      "How long a pause splits a turn online. Lower = more, shorter turns. " +
        "Applies to both providers and to the next session.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    VadSlider("Pause to split a turn", idleGap, OnlineBounds.idleGapMs, { "${it.toInt()} ms" }, { idleGap = it }) {
      settings.onlineIdleGapMs = idleGap.toInt()
    }

    HorizontalDivider()

    SectionTitle("About")
    // Tight inner spacing keeps the version block together; the parent Column's
    // 12.dp gap would otherwise scatter these one-line entries.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      val appName = stringResource(R.string.app_name)
      Text(
        remember(appName) {
          "$appName v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH}) / " +
            "${coreVersion()} / ${sherpaVersion()}"
        },
        style = MaterialTheme.typography.bodySmall,
      )
      remember { Models.manifest(context).models }.forEach { m ->
        Text("${m.id}: ${m.dir}", style = MaterialTheme.typography.bodySmall)
      }
      mtInfo?.let { Text(it.systemInfo, style = MaterialTheme.typography.bodySmall) }
    }
  }

  if (downloading) {
    AlertDialog(
      onDismissRequest = {}, // require an explicit Cancel; ignore outside taps
      title = { Text("Downloading") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          val frac = dlFraction
          if (frac == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
          } else {
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
          }
          Text(dlProgress, style = MaterialTheme.typography.bodySmall)
        }
      },
      confirmButton = {
        TextButton(onClick = { cancelFlag.set(true) }) { Text("Cancel") }
      },
      properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    )
  }

  pendingDelete?.let { id ->
    val spec = specs.first { it.id == id }
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Delete ${spec.displayName}?") },
      text = {
        Text(
          "Frees ${formatMb(modelStates[id]?.bytes ?: 0L)}. You can download it again later.",
        )
      },
      confirmButton = {
        TextButton(onClick = {
          pendingDelete = null
          // A session may have started while the dialog was open.
          if (RecordingService.onStopRequested != null) {
            modelMsg = "Stop the current session before deleting models."
            return@TextButton
          }
          deleting = true
          scope.launch {
            var threw = false
            try {
              withContext(Dispatchers.IO) {
                // Release the engine first: the GGUF is mmapped and ONNX engines hold their files open.
                unloadEngineFor(id)
                Models.delete(context, id)
              }
            } catch (e: Throwable) {
              threw = true
              modelMsg = "Delete failed: ${e.message}"
            } finally {
              refresh(id)
              deleting = false
            }
            // delete() is best-effort and does not throw, so check what is left on disk.
            if (!threw) {
              if ((modelStates[id]?.bytes ?: 0L) > 0) {
                modelMsg = "Could not delete all files of ${spec.displayName}."
              } else {
                engineStatus = ENGINE_IDLE
                modelMsg = "Deleted ${spec.displayName}."
              }
            }
          }
        }) { Text("Delete") }
      },
      dismissButton = {
        TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
      },
    )
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ModelRow(
  label: String,
  status: String,
  downloadEnabled: Boolean,
  deleteEnabled: Boolean,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    TextButton(enabled = downloadEnabled, onClick = onDownload) { Text("Download") }
    TextButton(enabled = deleteEnabled, onClick = onDelete) { Text("Delete") }
  }
}

// One option of an exclusive choice; the whole row is the touch target.
@Composable
private fun ChoiceRow(
  selected: Boolean,
  enabled: Boolean,
  title: String,
  description: String,
  onSelect: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // The row handles the click, so the button itself is not clickable (one tap target).
    RadioButton(selected = selected, onClick = null, enabled = enabled)
    Column(Modifier.weight(1f).padding(start = 8.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
  }
}

// Label + current value + a bounded slider. `onChange` updates UI state live;
// `onCommit` persists once the drag finishes (avoids writing on every tick).
@Composable
private fun VadSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  format: (Float) -> String,
  onChange: (Float) -> Unit,
  onCommit: () -> Unit,
) {
  Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onChange, onValueChangeFinished = onCommit, valueRange = range)
  }
}
