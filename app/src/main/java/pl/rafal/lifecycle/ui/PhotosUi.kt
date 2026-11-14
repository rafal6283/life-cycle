package pl.rafal.lifecycle.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs

data class Photo(val uri: Uri, val taken: Long)

object Photos {
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(ctx: Context) = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)

    suspend fun between(ctx: Context, from: Long, to: Long, limit: Int = 60): List<Photo> = withContext(Dispatchers.IO) {
        if (!allowed(ctx)) return@withContext emptyList()
        val out = mutableListOf<Photo>()
        try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
                arrayOf(from.toString(), to.toString()),
                "${MediaStore.Images.Media.DATE_TAKEN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += Photo(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getLong(1))
                }
            }
        } catch (_: Exception) {
        }
        out
    }

    suspend fun thumb(ctx: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null)
            else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(
                ctx.contentResolver, ContentUris.parseId(uri), MediaStore.Images.Thumbnails.MINI_KIND, null,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun open(ctx: Context, uri: Uri) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun Thumb(p: Photo, label: String?, size: Int) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, p.uri) { value = Photos.thumb(ctx, p.uri) }
    Column(Modifier.width(size.dp).clickable { Photos.open(ctx, p.uri) }) {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22888888))) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(size.dp), contentScale = ContentScale.Crop) }
        }
        Text(
            Fmt.time(p.taken) + (label?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Pasek zdjęć z galerii zrobionych w danym czasie. [label] podpisuje zdjęcie miejscem / aktywnością.
 */
@Composable
fun PhotoStrip(title: String, from: Long, to: Long, label: (Long) -> String? = { null }, size: Int = 96) {
    val ctx = LocalContext.current
    if (!remember { Prefs(ctx).photosOn }) return
    var tick by remember { mutableIntStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val allowed = remember(tick) { Photos.allowed(ctx) }
    val photos by produceState<List<Photo>>(emptyList(), from, to, tick) { value = Photos.between(ctx, from, to) }
    if (!allowed) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📷", modifier = Modifier.padding(end = 6.dp))
            TextButton(onClick = { perm.launch(Photos.permission) }) { Text("Pokaż zdjęcia z galerii z tego czasu") }
        }
        return
    }
    if (photos.isEmpty()) return
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            "$title · ${photos.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(photos, key = { it.uri.toString() }) { p -> Thumb(p, label(p.taken), size) }
        }
    }
}
