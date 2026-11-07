package com.tvpointer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvpointer.app.HidRemote.RemoteKey

/** Classic TV remote layout: D-pad with OK, Back/Home, and a volume rocker. */
@Composable
fun RemotePad(conn: HidRemote, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        DPad(conn)

        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            LabeledButton("Back") {
                TapButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onTap = conn::back)
            }
            LabeledButton("Home") {
                TapButton(Icons.Filled.Home, "Home", onTap = conn::home)
            }
        }

        VolumeRocker(conn)
    }
}

@Composable
private fun DPad(conn: HidRemote) {
    val ring = MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .size(264.dp)
            .clip(CircleShape)
            .background(ring),
    ) {
        val arrow = 80.dp // leaves a small gap around OK so presses don't overlap
        HoldKey(conn, RemoteKey.UP, Modifier.align(Alignment.TopCenter), CircleShape, arrow) {
            Icon(Icons.Filled.KeyboardArrowUp, "Up", Modifier.size(40.dp))
        }
        HoldKey(conn, RemoteKey.DOWN, Modifier.align(Alignment.BottomCenter), CircleShape, arrow) {
            Icon(Icons.Filled.KeyboardArrowDown, "Down", Modifier.size(40.dp))
        }
        HoldKey(conn, RemoteKey.LEFT, Modifier.align(Alignment.CenterStart), CircleShape, arrow) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", Modifier.size(40.dp))
        }
        HoldKey(conn, RemoteKey.RIGHT, Modifier.align(Alignment.CenterEnd), CircleShape, arrow) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", Modifier.size(40.dp))
        }
        HoldKey(
            conn, RemoteKey.OK, Modifier.align(Alignment.Center), CircleShape, 96.dp,
            filled = true,
        ) {
            Text("OK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VolumeRocker(conn: HidRemote) {
    val shape = RoundedCornerShape(40.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoldKey(conn, RemoteKey.VOLUME_DOWN, Modifier, shape, 80.dp) {
                Icon(Icons.Filled.Remove, "Volume down", Modifier.size(32.dp))
            }
            Spacer(Modifier.width(8.dp))
            TapButton(Icons.AutoMirrored.Filled.VolumeOff, "Mute", size = 56.dp, onTap = conn::mute)
            Spacer(Modifier.width(8.dp))
            HoldKey(conn, RemoteKey.VOLUME_UP, Modifier, shape, 80.dp) {
                Icon(Icons.Filled.Add, "Volume up", Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("VOL", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A button that sends key-down on touch and key-up on release, so holding it
 * repeats on the TV (scrolling lists, raising volume) like a physical remote.
 */
@Composable
private fun HoldKey(
    conn: HidRemote,
    key: RemoteKey,
    modifier: Modifier,
    shape: Shape,
    size: Dp,
    filled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        pressed -> scheme.primary.copy(alpha = 0.35f)
        filled -> scheme.primaryContainer
        else -> scheme.surfaceVariant
    }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(bg)
            .then(if (filled) Modifier.border(2.dp, scheme.primary, shape) else Modifier)
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    conn.keyDown(key)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        // Always release, even if the gesture is cancelled, so keys never stick.
                        conn.keyUp(key)
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (filled) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        ) { content() }
    }
}

@Composable
private fun TapButton(icon: ImageVector, label: String, size: Dp = 64.dp, onTap: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) scheme.primary.copy(alpha = 0.35f) else scheme.secondaryContainer)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTap()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = scheme.onSecondaryContainer)
    }
}

@Composable
private fun LabeledButton(label: String, button: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        button()
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
