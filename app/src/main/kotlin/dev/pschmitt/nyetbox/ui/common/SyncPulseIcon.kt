package dev.pschmitt.nyetbox.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private const val ROTATE_DURATION_MS = 1400

/**
 * A restrained continuous rotation for a "sync in progress" control icon (NBC-332) - reuses this
 * file's [rememberInfiniteTransition]-based idiom rather than a bespoke animation. The transition
 * is only composed while [syncing] is true, so it stops immediately (and stays at rest) the moment
 * a sync completes or fails instead of needing to be separately cancelled.
 */
@Composable
fun RotatingSyncIcon(
    icon: ImageVector,
    contentDescription: String?,
    syncing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (syncing) {
        val transition = rememberInfiniteTransition(label = "syncRotate")
        val rotation by
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(ROTATE_DURATION_MS, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "syncRotateAngle",
            )
        Icon(icon, contentDescription = contentDescription, modifier = modifier.rotate(rotation))
    } else {
        Icon(icon, contentDescription = contentDescription, modifier = modifier)
    }
}

private const val PULSE_DURATION_MS = 1400
private val PULSE_RING_PHASES = listOf(0f, 0.5f)
private val PULSE_BOX_SIZE = 36.dp
private val PULSE_BASE_RADIUS = 12.dp
private val PULSE_MAX_RADIUS = 18.dp
private val PULSE_STROKE_WIDTH = 2.dp
private const val PULSE_MAX_ALPHA = 0.35f

/**
 * Header object-type icon that grows expanding, fading rings outward from itself while [syncing] is
 * true - the in-progress signal for detail screens (device/generic item) that intentionally
 * suppress the large pull-to-refresh spinner over their content (see [SuppressiblePullToRefreshBox]
 * call sites). Reserves the same layout space whether or not [syncing] is true so the header
 * doesn't jump when a sync starts or ends.
 */
@Composable
fun SyncPulseIcon(
    icon: ImageVector,
    tint: Color,
    syncing: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "syncPulse")
    val progress by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(PULSE_DURATION_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "syncPulseProgress",
        )
    Box(modifier.size(PULSE_BOX_SIZE), contentAlignment = Alignment.Center) {
        if (syncing) {
            Box(
                Modifier.size(PULSE_BOX_SIZE).drawBehind {
                    val baseRadius = PULSE_BASE_RADIUS.toPx()
                    val maxRadius = PULSE_MAX_RADIUS.toPx()
                    PULSE_RING_PHASES.forEach { phase ->
                        val ringProgress = (progress + phase) % 1f
                        drawCircle(
                            color = tint.copy(alpha = (1f - ringProgress) * PULSE_MAX_ALPHA),
                            radius = baseRadius + (maxRadius - baseRadius) * ringProgress,
                            style = Stroke(width = PULSE_STROKE_WIDTH.toPx()),
                        )
                    }
                }
            )
        }
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
