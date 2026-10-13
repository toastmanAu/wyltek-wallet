package com.wyltek.wallet.prepaid.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepaidAuditScreen(onBack: () -> Unit, viewModel: PrepaidViewModel = viewModel()) {
    val events by viewModel.eventsFlow().collectAsState(initial = emptyList())
    var kindFilter by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shown = events.filter { kindFilter == null || it.kind == kindFilter }
    val grouped = shown.groupBy { it.actionId }.values.toList()
    Scaffold(topBar = { TopAppBar(title = { Row { Text("Audit log"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
        actions = { IconButton(onClick = { scope.launch {
            val csv = viewModel.allEventsCsv()
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, "blackbox-prepaid-audit.csv"); putExtra(Intent.EXTRA_TEXT, csv) }
            context.startActivity(Intent.createChooser(send, "Export audit CSV")) } }) { Icon(Icons.Default.Share, "Export CSV") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = kindFilter == null, onClick = { kindFilter = null }, label = { Text("all") })
                for (k in listOf(KIND_ISSUE, KIND_LOAD, KIND_VOID, KIND_CASH_OUT, KIND_SETTLE)) FilterChip(selected = kindFilter == k, onClick = { kindFilter = k }, label = { Text(k) })
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(grouped, key = { it.first().actionId }) { rows ->
                    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) { Column(Modifier.padding(8.dp)) {
                        Text("${rows.first().kind} · ${rows.first().uid}", color = NeonCyan)
                        for (e in rows.sortedBy { it.id }) EventRow(e)
                    } }
                }
            }
        }
    }
}
