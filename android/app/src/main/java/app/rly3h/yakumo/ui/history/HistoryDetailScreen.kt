package app.rly3h.yakumo.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.rly3h.yakumo.data.LoggedUtterance
import app.rly3h.yakumo.data.SessionLog
import app.rly3h.yakumo.data.SessionStore
import app.rly3h.yakumo.translate.Speaker
import app.rly3h.yakumo.ui.session.TurnCard
import app.rly3h.yakumo.ui.session.labelForFlores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HistoryDetailScreen(id: String, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val session by produceState<SessionLog?>(initialValue = null, id) {
    value = withContext(Dispatchers.IO) { SessionStore.load(context, id) }
  }

  val s = session
  if (s == null) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
      Text("Session not found.", color = MaterialTheme.colorScheme.error)
    }
    return
  }

  LazyColumn(
    modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      Text(
        formatTime(s.startedAt),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp),
      )
    }
    items(s.utterances.size) { i -> LoggedTurnCard(s.utterances[i]) }
  }
}

@Composable
private fun LoggedTurnCard(u: LoggedUtterance) {
  // An unrecognised speaker string (a newer or hand-edited file) falls back to the Listen layout.
  val speaker = Speaker.values().firstOrNull { it.name == u.speaker }
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (speaker == Speaker.ME) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
    ),
  ) {
    if (speaker != null) {
      // Same heading as the live view ("You · JA → FR").
      TurnCard(u.transcript, u.srcLang, u.tgtLang, u.translation, speaker)
      return@Card
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        "${labelForFlores(u.srcLang)}  ${u.transcript}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        "${labelForFlores(u.tgtLang)}  ${u.translation}",
        style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}
