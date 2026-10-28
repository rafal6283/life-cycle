package pl.rafal.lifecycle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import pl.rafal.lifecycle.domain.ExchangeImport

/*
 * Dane z współpracujących aplikacji (Rytm Snu, Kreska, Polar EKG) są delikatnie wyróżnione:
 * cienka obwódka zamiast samego tła i mała etykieta „z …” z ikoną łącza.
 */

/** „z Rytmu Snu”, „z Kreski”, „z Polar EKG”. */
fun importedFrom(source: String): String = when (source) {
    ExchangeImport.SRC_NIGHT, ExchangeImport.SRC_NIGHT_NEW -> "z Rytmu Snu"
    ExchangeImport.SRC_KRESKA -> "z Kreski"
    ExchangeImport.SRC_ECG -> "z Polar EKG"
    else -> "zaimportowane"
}

@Composable
fun ImportedTag(source: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Link, null, tint = c, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(importedFrom(source), style = MaterialTheme.typography.labelSmall, color = c)
    }
}

/** Cienka obwódka karty z danymi zaimportowanymi. */
@Composable
fun Modifier.importedBorder(shape: Shape = RoundedCornerShape(18.dp)): Modifier =
    this.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
