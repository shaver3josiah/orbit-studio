package com.orbitstudio.capture.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlinx.coroutines.delay

enum class ToastTone { INFO, SUCCESS, WARNING, DANGER }

data class ToastState(val message: String, val tone: ToastTone = ToastTone.INFO)

private fun toneColor(tone: ToastTone) = when (tone) {
    ToastTone.INFO -> OrbitColors.textPrimary
    ToastTone.SUCCESS -> OrbitColors.success
    ToastTone.WARNING -> OrbitColors.warning
    ToastTone.DANGER -> OrbitColors.danger
}

// Single bottom-anchored pill. State is hoisted: pass null to hide, a ToastState to show.
// Auto-dismisses after 2.6s by calling onDismiss — caller owns the state, so a new toast
// while one is showing simply replaces it (never stacks).
@Composable
fun CoachToast(toast: ToastState?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            onDismiss()
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 3 },
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .background(OrbitColors.elevated, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = toast?.message.orEmpty(),
                    color = toast?.let { toneColor(it.tone) } ?: OrbitColors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
