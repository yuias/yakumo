package app.rly3h.yakumo.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.rly3h.yakumo.data.SessionLog
import app.rly3h.yakumo.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun formatTime(millis: Long): String =
  java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    .format(java.util.Date(millis))

@Composable
fun SessionHistoryScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val sessions = remember { mutableStateListOf<SessionLog>() }
  var reload by remember { mutableStateOf(0) }

  LaunchedEffect(reload) {
    val loaded = withContext(Dispatchers.IO) { SessionStore.list(context) }
    sessions.clear()
    sessions.addAll(loaded)
  }

  if (sessions.isEmpty()) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
      Text(
        "No sessions yet. Start a New session to see it here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    return
  }

  LazyColumn(
    modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(sessions, key = { it.id }) { s ->
      SessionRow(
        session = s,
        onOpen = { onOpen(s.id) },
        onDelete = {
          SessionStore.delete(context, s.id)
          reload++
        },
      )
    }
  }
}

@Composable
private fun SessionRow(session: SessionLog, onOpen: () -> Unit, onDelete: () -> Unit) {
  val preview = session.utterances.firstOrNull()?.transcript.orEmpty()
  Card(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
    Row(
      Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(formatTime(session.startedAt), style = MaterialTheme.typography.titleSmall)
        Text(
          "${session.utterances.size} turn${if (session.utterances.size == 1) "" else "s"}" +
            (if (session.mode == "CONVERSATION") " · Conversation" else "") +
            if (preview.isNotBlank()) " · $preview" else "",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
          maxLines = 1,
        )
      }
      TextButton(onClick = onDelete) { Text("Delete") }
    }
  }
}
