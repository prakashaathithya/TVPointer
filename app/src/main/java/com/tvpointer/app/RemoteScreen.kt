package com.tvpointer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun RemoteScreen(vm: RemoteViewModel, tvName: String) {
    val conn = vm.remote
    val speed by vm.speed.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    var keyboardOpen by remember { mutableStateOf(false) }
    // The trackpad's gesture handler is created once, so read speed through State.
    val liveSpeed by rememberUpdatedState(speed)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Connected", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(tvName, style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = conn::disconnect) {
                    Icon(Icons.Filled.LinkOff, contentDescription = "Disconnect")
                }
            }

            // Trackpad
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                    .trackpad(
                        onMove = { dx, dy ->
                            val x = dx / density
                            val y = dy / density
                            // Faster finger movement travels further (pointer acceleration).
                            val accel = 1f + min(hypot(x, y) / 6f, 2f)
                            // ~2 TV mouse counts per dp of finger travel at normal speed.
                            val k = 2f * liveSpeed * accel
                            conn.move(x * k, y * k)
                        },
                        onScroll = { dx, dy -> conn.scroll(dx / density, dy / density, step = 18f) },
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            conn.click()
                        },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            conn.press()
                        },
                        onLongRelease = { conn.release() },
                        onTwoFingerTap = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            conn.back()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Drag to move · Tap to click\nHold for long press (keep holding and move to drag)\nTwo fingers to scroll · Two-finger tap = Back",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            if (keyboardOpen) {
                KeyboardPanel(conn)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = speed, onValueChange = vm::setSpeed,
                        valueRange = 0.4f..2.5f, modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
            }

            // Button row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledTonalIconButton(onClick = { conn.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                FilledTonalIconButton(onClick = { conn.home() }) {
                    Icon(Icons.Filled.Home, contentDescription = "Home")
                }
                FilledTonalIconButton(onClick = { keyboardOpen = !keyboardOpen }) {
                    Icon(
                        if (keyboardOpen) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                        contentDescription = if (keyboardOpen) "Hide keyboard" else "Keyboard",
                    )
                }
            }
        }
    }
}

/**
 * Typing panel. Whatever changes in the phone's text field is typed on the TV as
 * real key presses: removed characters become Backspace, new ones become keys.
 * This also works with autocorrect and swipe typing. A Bluetooth keyboard can only
 * send keys from a US layout, so other scripts and emoji are skipped.
 */
@Composable
private fun KeyboardPanel(conn: HidRemote) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var skippedNote by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    fun sendEnter() {
        conn.enter()
        value = TextFieldValue("")
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = { new ->
                    val old = value.text
                    val prefix = old.commonPrefixWith(new.text).length
                    // Only characters that were actually typed exist on the TV.
                    val removed = old.substring(prefix).count { HidReports.keyFor(it) != null }
                    conn.backspace(removed)
                    if (conn.type(new.text.substring(prefix)) > 0) skippedNote = true
                    value = new
                },
                placeholder = { Text("Type on the TV…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendEnter() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = {
                // Deletes on the TV even when the phone field is already empty.
                conn.backspace()
                val t = value.text
                if (t.isNotEmpty()) {
                    val cut = t.offsetByCodePoints(t.length, -1)
                    value = TextFieldValue(t.substring(0, cut))
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace")
            }
            IconButton(onClick = ::sendEnter) {
                Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = "Enter")
            }
        }
        if (skippedNote) {
            Text(
                "Only English letters, numbers and symbols can be sent to the TV.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
