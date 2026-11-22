package org.fetchx.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fetchx.app.engine.AUDIO_QUALITIES
import org.fetchx.app.engine.AudioFormatPref
import org.fetchx.app.engine.CodecPref
import org.fetchx.app.engine.ContainerPref
import org.fetchx.app.engine.FormatInfo
import org.fetchx.app.engine.MediaType
import org.fetchx.app.engine.ProbeResult
import org.fetchx.app.ui.AddState
import org.fetchx.app.ui.AppViewModel

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FormatRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun FormatList(
    title: String,
    formats: List<FormatInfo>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Label(title)
    Column {
        FormatRow("None", selectedId == null) { onSelect(null) }
        formats.forEach { f -> FormatRow(f.label, selectedId == f.id) { onSelect(f.id) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtOptionsPanel(vm: AppViewModel, st: AddState, probe: ProbeResult) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(probe.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        val meta = listOfNotNull(
            probe.uploader,
            probe.durationSec.takeIf { it > 0 }?.let { d ->
                val h = d / 3600
                val m = (d % 3600) / 60
                val s = d % 60
                if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
            },
        ).joinToString(" • ")
        if (meta.isNotBlank()) {
            Text(meta, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Custom format selection", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = st.custom,
                onCheckedChange = { on ->
                    vm.updateAdd {
                        it.copy(
                            custom = on,
                            videoId = it.videoId ?: it.probe?.videoFormats?.firstOrNull()?.id,
                            audioId = it.audioId ?: it.probe?.audioFormats?.firstOrNull()?.id,
                        )
                    }
                },
            )
        }

        if (!st.custom) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MediaType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = st.type == t,
                        onClick = { vm.updateAdd { it.copy(type = t) } },
                        shape = SegmentedButtonDefaults.itemShape(i, MediaType.entries.size),
                    ) { Text(t.label, maxLines = 1, fontSize = 12.sp) }
                }
            }

            if (st.type != MediaType.AUDIO_ONLY) {
                Label("Resolution")
                val heights = listOf(0) + probe.heights
                ChipRow(
                    items = heights,
                    selected = heights.getOrElse(st.selected) { 0 },
                    label = { if (it == 0) "Best" else "${it}p" },
                    onSelect = { h -> vm.selectQuality(heights.indexOf(h).coerceAtLeast(0)) },
                )
                Label("Video codec")
                ChipRow(CodecPref.entries, st.codec, { it.label }) { c -> vm.updateAdd { it.copy(codec = c) } }
                Label("Container")
                ChipRow(ContainerPref.entries, st.container, { it.label }) { c -> vm.updateAdd { it.copy(container = c) } }
            } else {
                AudioChips(vm, st)
            }
        } else {
            FormatList("Video stream", probe.videoFormats, st.videoId) { id -> vm.updateAdd { it.copy(videoId = id) } }
            FormatList("Audio stream", probe.audioFormats, st.audioId) { id -> vm.updateAdd { it.copy(audioId = id) } }
            if (st.videoId != null) {
                Label("Container")
                ChipRow(ContainerPref.entries, st.container, { it.label }) { c -> vm.updateAdd { it.copy(container = c) } }
            } else if (st.audioId != null) {
                AudioChips(vm, st)
            }
        }

        Label("Extras")
        Column {
            CheckRow("Embed metadata and chapters", st.embedMetadata) { v -> vm.updateAdd { it.copy(embedMetadata = v) } }
            CheckRow("Embed thumbnail", st.embedThumbnail) { v -> vm.updateAdd { it.copy(embedThumbnail = v) } }
            CheckRow("Embed subtitles (English)", st.subtitles) { v -> vm.updateAdd { it.copy(subtitles = v) } }
            CheckRow("Remove sponsor segments (SponsorBlock)", st.sponsorBlock) { v -> vm.updateAdd { it.copy(sponsorBlock = v) } }
        }
    }
}

@Composable
private fun AudioChips(vm: AppViewModel, st: AddState) {
    Label("Audio format")
    ChipRow(AudioFormatPref.entries, st.audioFmt, { it.label }) { f -> vm.updateAdd { it.copy(audioFmt = f) } }
    if (st.audioFmt != AudioFormatPref.ORIGINAL) {
        Label("Audio quality")
        ChipRow(AUDIO_QUALITIES, st.audioQuality, { if (it == 0) "Best" else "${it} kbps" }) { q ->
            vm.updateAdd { it.copy(audioQuality = q) }
        }
    }
}
