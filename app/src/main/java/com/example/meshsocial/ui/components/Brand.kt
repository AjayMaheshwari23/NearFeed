package com.example.meshsocial.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshsocial.R

/** The bare NEAR-FEED mark glyph (no wordmark), tinted to theme. */
@Composable
fun NearFeedMark(
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.ic_nearfeed_mark),
        contentDescription = "NEAR-FEED",
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/**
 * Reusable NEAR-FEED product mark + wordmark. Single source of truth for the
 * logo across onboarding, header and empty states.
 */
@Composable
fun NearFeedBrandMark(
    modifier: Modifier = Modifier,
    showWordmark: Boolean = true,
    logoSize: Dp = 56.dp,
    wordmarkSize: Int = 22,
    horizontal: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (horizontal) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            NearFeedMark(size = logoSize, tint = tint)
            if (showWordmark) {
                Spacer(Modifier.size(8.dp))
                Wordmark(wordmarkSize)
            }
        }
    } else {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            NearFeedMark(size = logoSize, tint = tint)
            if (showWordmark) {
                Spacer(Modifier.size(10.dp))
                Wordmark(wordmarkSize)
            }
        }
    }
}

@Composable
private fun Wordmark(size: Int) {
    Text(
        "NEAR-FEED",
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            fontSize = size.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
