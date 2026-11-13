package com.foqos.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Numeric text input and slider share the same committed integer state. */
@Composable
fun DurationSliderField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    enabled: Boolean = true,
    unit: String = "分",
    step: Int = 1,
    compact: Boolean = false,
) {
    require(!range.isEmpty())
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused) text = value.toString()
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            if (compact) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            } else {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    if (raw.length <= 6 && raw.all(Char::isDigit)) {
                        text = raw
                        raw.toIntOrNull()?.takeIf { it in range }?.let(onValueChange)
                    }
                },
                modifier = Modifier.width(if (compact) 100.dp else 110.dp).onFocusChanged {
                    val wasFocused = focused
                    focused = it.isFocused
                    if (wasFocused && !it.isFocused) {
                        text = value.toString()
                    }
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text(unit) },
            )
        }
        Slider(
            value = value.coerceIn(range).toFloat(),
            onValueChange = { new ->
                val quantized = (((new - range.first) / step).roundToInt() * step +
                    range.first).coerceIn(range)
                onValueChange(quantized)
                text = quantized.toString()
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            enabled = enabled && range.first != range.last,
        )
    }
}
