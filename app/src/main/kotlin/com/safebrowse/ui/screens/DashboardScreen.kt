package com.safebrowse.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safebrowse.ui.theme.*
import com.safebrowse.viewmodel.DashboardViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToBlocklist: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRequestStopProtection: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val running by viewModel.isVpnRunning.collectAsState()
    val count by viewModel.blockedDomainCount.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collectLatest { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SafeBrowse", fontFamily = FontFamily.Serif) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Night
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            StatusCard(running, count)

            PowerButton(running) {
                // Starting is free. Stopping goes through the chapter.
                if (running) onRequestStopProtection() else viewModel.startProtection()
            }

            if (running) {
                Text(
                    "Stopping takes about ${viewModel.gateMinutes} minutes of reading.",
                    color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = onNavigateToBlocklist,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
            ) {
                Icon(Icons.Default.List, null, tint = Muted)
                Spacer(Modifier.width(8.dp))
                Text("Blocked sites ($count)", color = Pale)
            }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, count: Int) {
    val accent by animateColorAsState(
        if (running) Gilt else Garnet, tween(500), label = "accent"
    )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Depth),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (running) "Filtering is on" else "Filtering is off",
                    color = Pale, fontSize = 17.sp, fontFamily = FontFamily.Serif
                )
                Text(
                    if (running) "$count domains blocked, including in incognito"
                    else "Nothing is being blocked right now",
                    color = Muted, fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun PowerButton(running: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (running) Gilt else Depth, tween(500), label = "power")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(128.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = color,
                contentColor = if (running) Night else Muted
            )
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                if (running) "Stop protection" else "Start protection",
                Modifier.size(56.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(if (running) "Tap to stop" else "Tap to start", color = Muted, fontSize = 14.sp)
    }
}
