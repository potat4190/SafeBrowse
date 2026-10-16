package com.safebrowse.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safebrowse.dns.DnsForwarder
import com.safebrowse.gate.Romans6
import com.safebrowse.ui.theme.*
import com.safebrowse.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val startOnBoot by viewModel.startOnBoot.collectAsState()
    val dns by viewModel.dnsServer.collectAsState()
    val readings by viewModel.readings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showDns by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.uiMessage.collectLatest { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontFamily = FontFamily.Serif) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Night
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Protection") }
            item {
                ListItem(
                    headlineContent = { Text("Start after reboot", color = Pale) },
                    supportingContent = {
                        Text("Filtering resumes when the phone restarts", color = Muted, fontSize = 13.sp)
                    },
                    leadingContent = { Icon(Icons.Default.RestartAlt, null, tint = Muted) },
                    trailingContent = {
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = viewModel::setStartOnBoot,
                            colors = SwitchDefaults.colors(checkedThumbColor = Gilt)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Night)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Upstream DNS", color = Pale) },
                    supportingContent = { Text(dns, color = Muted, fontSize = 13.sp) },
                    leadingContent = { Icon(Icons.Default.Dns, null, tint = Muted) },
                    modifier = Modifier.clickable { showDns = true },
                    colors = ListItemDefaults.colors(containerColor = Night)
                )
            }

            item { SectionHeader("Blocklist") }
            item {
                ListItem(
                    headlineContent = { Text("Import a list", color = Pale) },
                    supportingContent = { Text("One domain per line", color = Muted, fontSize = 13.sp) },
                    leadingContent = { Icon(Icons.Default.Download, null, tint = Muted) },
                    modifier = Modifier.clickable { showImport = true },
                    colors = ListItemDefaults.colors(containerColor = Night)
                )
            }

            item { SectionHeader("The gate") }
            item {
                ListItem(
                    headlineContent = { Text("Romans 6", color = Pale) },
                    supportingContent = {
                        Text(
                            "23 verses, about ${Romans6.totalReadingMinutes()} minutes. " +
                                "Read in full $readings time${if (readings == 1) "" else "s"}.",
                            color = Muted, fontSize = 13.sp
                        )
                    },
                    leadingContent = { Icon(Icons.Default.MenuBook, null, tint = Gilt) },
                    colors = ListItemDefaults.colors(containerColor = Night)
                )
            }
        }
    }

    if (showDns) {
        var selected by remember { mutableStateOf(dns) }
        AlertDialog(
            onDismissRequest = { showDns = false },
            containerColor = Depth,
            title = { Text("Upstream DNS", color = Pale) },
            text = {
                Column {
                    listOf(
                        DnsForwarder.CLOUDFLARE_DNS to "Cloudflare",
                        DnsForwarder.GOOGLE_DNS to "Google",
                        DnsForwarder.QUAD9_DNS to "Quad9"
                    ).forEach { (ip, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selected == ip,
                                onClick = { selected = ip },
                                colors = RadioButtonDefaults.colors(selectedColor = Gilt)
                            )
                            Text("$name  ($ip)", color = Pale)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setDnsServer(selected); showDns = false }) {
                    Text("Apply", color = Gilt)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDns = false }) { Text("Cancel", color = Muted) }
            }
        )
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            containerColor = Depth,
            title = { Text("Import domains", color = Pale) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text("example.com\nanother.com", color = Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Pale, unfocusedTextColor = Pale,
                        focusedBorderColor = Gilt, unfocusedBorderColor = LineC,
                        cursorColor = Gilt
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.importBlocklist(text); showImport = false },
                    enabled = text.isNotBlank()
                ) { Text("Import", color = Gilt) }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("Cancel", color = Muted) }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title, color = Gilt, fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp)
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = LineC)
}
