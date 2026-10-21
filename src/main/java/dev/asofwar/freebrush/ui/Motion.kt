package dev.asofwar.freebrush.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Fades the element in and lifts it 24 dp into place, [index] steps of 70 ms after the screen appears,
 * so cards arrive one after another instead of all at once.
 */
fun Modifier.staggeredEntrance(index: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(70L * index)
        progress.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}
