package app.styli.ink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable

/**
 * What every accordion (a section or folder that folds open and shut) uses, so they all move the same way: the
 * content grows down from its heading while fading in, and folds back up a little faster.
 */
@Composable
fun Reveal(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        open,
        enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(220, 40)),
        exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing)) + fadeOut(tween(140)),
    ) { Column { content() } }
}
