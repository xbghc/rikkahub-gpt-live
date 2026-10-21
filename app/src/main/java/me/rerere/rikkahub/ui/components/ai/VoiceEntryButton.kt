package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Voice

@Composable
internal fun VoiceEntryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onConfigure: (() -> Unit)? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = 36.dp)
                .testTag("voice_entry_button")
                .semantics { contentDescription = "语音对话" },
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(HugeIcons.Voice, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("语音", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        if (onConfigure != null) {
            IconButton(onClick = onConfigure, modifier = Modifier.testTag("voice_configure_button")) {
                Icon(HugeIcons.Settings03, contentDescription = "语音设置", modifier = Modifier.size(18.dp))
            }
        }
    }
}
