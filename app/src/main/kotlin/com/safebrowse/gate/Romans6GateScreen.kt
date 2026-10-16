package com.safebrowse.gate

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.pow

// Palette shared with the Windows build.
private val Night  = Color(0xFF0B1022)
private val Depth  = Color(0xFF151C36)
private val LineC  = Color(0xFF232B4A)
private val Pale   = Color(0xFFE6EAF6)
private val Muted  = Color(0xFF8B95B5)
private val Gilt   = Color(0xFFC8A24A)
private val Garnet = Color(0xFFE39AA2)

/**
 * The gate. Nothing is unlocked until the reader has been through all 23
 * verses at reading pace and answered a word check from one of them.
 *
 * Leaving mid-chapter resets to verse 1 — including via the system back
 * gesture, which [BackHandler] intercepts. Progress is saveable state, so a
 * rotation, theme change or background process death does not reset it.
 */
@Composable
fun Romans6GateScreen(
    intent: GateIntent,
    onPassed: () -> Unit,
    onAbandoned: () -> Unit
) {
    var verseIndex by rememberSaveable { mutableIntStateOf(0) }
    var atWordCheck by rememberSaveable { mutableStateOf(false) }
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }

    val total = Romans6.verses.size
    val progress = if (atWordCheck) 1f else verseIndex.toFloat() / total

    // The room lifts out of the dark as the chapter goes on: verse 1 is nearly
    // black, verse 23 is lit. Held back by a curve so most of it happens late.
    val bg by animateColorAsState(
        targetValue = Color(
            red   = (11 + progress * 27) / 255f,
            green = (16 + progress * 34) / 255f,
            blue  = (34 + progress * 54) / 255f
        ),
        animationSpec = tween(600),
        label = "gateBackground"
    )
    val glow by animateFloatAsState(
        targetValue = progress.toDouble().pow(2.6).toFloat() * 0.9f,
        animationSpec = tween(600),
        label = "gateGlow"
    )

    BackHandler(enabled = true) {
        if (verseIndex > 0 || atWordCheck) showLeaveDialog = true else onAbandoned()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        // Gold light behind the text, growing toward the end of the chapter.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Gilt.copy(alpha = glow * 0.30f), Color.Transparent)
                    )
                )
        )

        Column(Modifier.fillMaxSize()) {
            GateBar(
                progress = progress,
                label = if (atWordCheck) "one word left" else "${verseIndex + 1} of $total",
                onQuit = {
                    if (verseIndex > 0 || atWordCheck) showLeaveDialog = true else onAbandoned()
                }
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!atWordCheck) {
                    VersePane(
                        index = verseIndex,
                        total = total,
                        onNext = {
                            if (verseIndex < total - 1) verseIndex++ else atWordCheck = true
                        }
                    )
                } else {
                    WordCheckPane(
                        actionLabel = intent.label,
                        onCorrect = onPassed,
                        onWrong = {
                            atWordCheck = false
                            verseIndex = 0
                        }
                    )
                }
            }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            containerColor = Depth,
            title = { Text("Leave the chapter?", color = Pale) },
            text = {
                Text(
                    "Leave now and it starts over from verse 1.",
                    color = Muted
                )
            },
            confirmButton = {
                TextButton(onClick = { showLeaveDialog = false; onAbandoned() }) {
                    Text("Leave", color = Garnet)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text("Stay", color = Pale)
                }
            }
        )
    }
}

@Composable
private fun GateBar(progress: Float, label: String, onQuit: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Gilt,
            trackColor = Pale.copy(alpha = 0.16f),
            drawStopIndicator = {}
        )
        Text(label, color = Muted, fontSize = 12.sp)
        TextButton(onClick = onQuit, contentPadding = PaddingValues(4.dp)) {
            Text("\u00D7", color = Muted, fontSize = 20.sp)
        }
    }
}

@Composable
private fun VersePane(index: Int, total: Int, onNext: () -> Unit) {
    val text = Romans6.verses[index]
    val dwell = remember(index) { Romans6.dwellFor(text) }

    var elapsed by remember(index) { mutableLongStateOf(0L) }
    val ready = elapsed >= dwell

    // Hold the button shut until the verse has had time to actually be read.
    LaunchedEffect(index) {
        val step = 50L
        var t = 0L
        while (t < dwell) {
            delay(step)
            t += step
            elapsed = t
        }
        elapsed = dwell
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Romans 6:${index + 1}",
            color = Gilt,
            fontSize = 15.sp,
            fontFamily = FontFamily.Serif
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = text,
            color = Pale,
            fontSize = 24.sp,
            lineHeight = 37.sp,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))

        DwellButton(
            label = if (index == total - 1) "Finish the chapter" else "Next verse",
            fraction = (elapsed.toFloat() / dwell).coerceIn(0f, 1f),
            enabled = ready,
            onClick = onNext
        )
    }
}

/**
 * A button that fills with light as its wait elapses, then becomes tappable.
 * The fill is the only thing on screen telling you how long is left, which is
 * deliberate — it should feel like waiting, not like a countdown.
 */
@Composable
private fun DwellButton(
    label: String,
    fraction: Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Pale else Depth)
            .widthIn(min = 200.dp)
    ) {
        if (!enabled) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Gilt.copy(alpha = 0.20f))
                    .align(Alignment.CenterStart)
            )
        }
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.align(Alignment.Center),
            colors = ButtonDefaults.textButtonColors(
                contentColor = Night,
                disabledContentColor = Muted
            ),
            contentPadding = PaddingValues(horizontal = 34.dp, vertical = 14.dp)
        ) {
            Text(label, fontSize = 15.sp)
        }
    }
}

@Composable
private fun WordCheckPane(
    actionLabel: String,
    onCorrect: () -> Unit,
    onWrong: () -> Unit
) {
    // Saved so a rotation can't reroll the question for an easier one.
    val check = rememberSaveable(saver = WordCheckSaver) { Romans6.makeWordCheck() }
    var answer by rememberSaveable { mutableStateOf("") }
    // Saved too: losing it mid-pause would skip the reset and allow a retry.
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // IME Done and the button can both fire; passing twice would pop the
    // back stack twice and count two readings.
    var passed by rememberSaveable { mutableStateOf(false) }

    // Rebuild the verse with the chosen word replaced by a rule.
    val masked = remember(check) {
        check.words.mapIndexed { idx, w ->
            if (idx == check.wordIndex) "_____" + w.filter { !it.isLetter() } else w
        }.joinToString(" ")
    }

    fun submit() {
        if (passed || error != null) return
        val given = answer.trim().lowercase().filter { it.isLetter() }
        if (given == check.answer) {
            passed = true
            onCorrect()
        } else {
            error = "Not the word. Back to the beginning."
        }
    }

    // Give the message a beat to land before restarting the chapter.
    LaunchedEffect(error) {
        if (error != null) {
            delay(1400)
            onWrong()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .widthIn(max = 420.dp)
    ) {
        Text(
            "One word before you go",
            color = Pale,
            fontSize = 22.sp,
            fontFamily = FontFamily.Serif
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Fill the gap from Romans 6:${check.verseIndex + 1}. " +
                "Get it wrong and the chapter starts again.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))
        Text(
            masked,
            color = Pale,
            fontSize = 19.sp,
            lineHeight = 31.sp,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it; error = null },
            singleLine = true,
            enabled = error == null,
            placeholder = { Text("the missing word", color = Muted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Pale,
                unfocusedTextColor = Pale,
                focusedBorderColor = Gilt,
                unfocusedBorderColor = LineC,
                cursorColor = Gilt,
                focusedContainerColor = Depth,
                unfocusedContainerColor = Depth
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        Text(
            error ?: "",
            color = Garnet,
            fontSize = 14.sp,
            modifier = Modifier.height(20.dp)
        )
        Spacer(Modifier.height(14.dp))

        Button(
            onClick = { submit() },
            enabled = answer.isNotBlank() && error == null,
            colors = ButtonDefaults.buttonColors(
                containerColor = Pale,
                contentColor = Night,
                disabledContainerColor = Depth,
                disabledContentColor = Muted
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 34.dp, vertical = 14.dp)
        ) {
            Text(actionLabel, fontSize = 15.sp)
        }
    }
}

private val WordCheckSaver = listSaver<Romans6.WordCheck, Any>(
    save = { listOf(it.verseIndex, it.wordIndex, it.answer) },
    restore = {
        val verse = it[0] as Int
        Romans6.WordCheck(verse, it[1] as Int, it[2] as String, Romans6.verses[verse].split(" "))
    }
)
