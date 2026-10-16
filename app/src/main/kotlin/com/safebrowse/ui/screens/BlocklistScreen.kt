package com.safebrowse.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safebrowse.database.entity.BlockedDomain
import com.safebrowse.ui.theme.*
import com.safebrowse.viewmodel.BlocklistViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocklistScreen(
    onNavigateUp: () -> Unit,
    onRequestUnblock: (BlockedDomain) -> Unit,
    viewModel: BlocklistViewModel = hiltViewModel()
) {
    val domains by viewModel.domains.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val showAdd by viewModel.showAddDialog.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.uiMessage.collectLatest { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked (${domains.size})", fontFamily = FontFamily.Serif) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showAddDialog() },
                containerColor = Gilt, contentColor = Night
            ) { Icon(Icons.Default.Add, "Block a site") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Night
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search", color = Muted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, "Clear", tint = Muted)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Pale, unfocusedTextColor = Pale,
                    focusedBorderColor = Gilt, unfocusedBorderColor = LineC,
                    focusedContainerColor = Depth, unfocusedContainerColor = Depth,
                    cursorColor = Gilt
                )
            )

            if (domains.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) "Nothing matches that."
                        else "Nothing blocked yet.\nTap + to add the site you keep going back to.",
                        color = Muted, textAlign = TextAlign.Center, lineHeight = 24.sp
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(domains, key = { it.id }) { domain ->
                        DomainRow(domain) { onRequestUnblock(domain) }
                        HorizontalDivider(color = LineC)
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDomainDialog(
            onDismiss = { viewModel.dismissAddDialog() },
            onConfirm = { viewModel.addDomain(it) }
        )
    }
}

@Composable
private fun DomainRow(domain: BlockedDomain, onUnblock: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(domain.domain, color = Pale, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                if (domain.isDefault) "From the starter list" else "Added by you",
                color = Muted, fontSize = 12.sp
            )
        },
        trailingContent = {
            // No delete icon and no toggle — the only way off this list runs
            // through the chapter.
            TextButton(onClick = onUnblock) { Text("Unblock", color = Muted, fontSize = 13.sp) }
        },
        colors = ListItemDefaults.colors(containerColor = Night)
    )
}

@Composable
private fun AddDomainDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Depth,
        title = { Text("Block a site", color = Pale, fontFamily = FontFamily.Serif) },
        text = {
            Column {
                Text("Subdomains are covered automatically.", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text("example.com", color = Muted) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Pale, unfocusedTextColor = Pale,
                        focusedBorderColor = Gilt, unfocusedBorderColor = LineC,
                        cursorColor = Gilt
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }, enabled = input.isNotBlank()) {
                Text("Block", color = Gilt)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
