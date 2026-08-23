package me.rerere.rikkahub.ui.pages.chat

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun AssistantBackground(
    setting: Settings,
    modifier: Modifier,
    animateGradient: Boolean = true,
    animateImage: Boolean = false,
) {
    val assistant = setting.getCurrentAssistant()
    if (assistant.useGradientBackground) {
        MeshGradientBackground(modifier = modifier, animated = animateGradient)
        return
    }
    if (assistant.background != null) {
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
        val motion = resolveTavernBackgroundMotion(
            animateImage = animateImage,
            hasBackground = true,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
            pageVisible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
        )
        val phase = if (motion.enabled) {
            val transition = rememberInfiniteTransition(label = "tavern_background")
            val animatedPhase by transition.animateFloat(
                initialValue = 0f,
                targetValue = (Math.PI * 2.0).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(motion.durationMillis, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "tavern_background_phase",
            )
            animatedPhase
        } else {
            0f
        }
        val backgroundColor = MaterialTheme.colorScheme.background
        val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
        Box(modifier = modifier) {
            AsyncImage(
                model = assistant.background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
                    .graphicsLayer {
                        if (motion.enabled) {
                            val wave = ((sin(phase) + 1f) / 2f)
                            val scale = motion.minScale + (motion.maxScale - motion.minScale) * wave
                            val travel = min(size.width, size.height)
                            scaleX = scale
                            scaleY = scale
                            translationX = travel * motion.translationFraction * cos(phase)
                            translationY = travel * motion.translationFraction * 0.65f * sin(phase)
                        }
                    }
            )

            // 全屏渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                backgroundColor.copy(alpha = 0.2f),
                                backgroundColor.copy(alpha = 0.5f)
                            )
                        )
                    )
            )
        }
    }
}
