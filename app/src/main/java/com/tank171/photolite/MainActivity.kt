package com.tank171.photolite

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DecimalFormat
import kotlin.math.max
import kotlin.math.roundToInt

private val Navy = Color(0xFF0A1020)
private val Panel = Color(0xFF151F33)
private val Cyan = Color(0xFF39D7F2)
private val Muted = Color(0xFF9AAAC1)

private enum class ImageFormat(val label: String, val mime: String, val extension: String, val bitmapFormat: Bitmap.CompressFormat) {
    JPEG("JPG / JPEG", "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG),
    PNG("PNG", "image/png", "png", Bitmap.CompressFormat.PNG),
    WEBP("WebP", "image/webp", "webp", Bitmap.CompressFormat.WEBP_LOSSY)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Cyan, background = Navy, surface = Panel,
                onPrimary = Navy, onBackground = Color.White, onSurface = Color.White
            )) { PhotoLiteScreen() }
        }
    }

    @Composable
    private fun PhotoLiteScreen() {
        var source by remember { mutableStateOf<Uri?>(null) }
        var sourceSize by remember { mutableStateOf<Long?>(null) }
        var targetKb by remember { mutableFloatStateOf(200f) }
        var quality by remember { mutableFloatStateOf(82f) }
        var maxWidth by remember { mutableFloatStateOf(1600f) }
        var format by remember { mutableStateOf(ImageFormat.JPEG) }
        var output by remember { mutableStateOf<ByteArray?>(null) }
        var savedUri by remember { mutableStateOf<Uri?>(null) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Pilih foto untuk mulai mengolah gambar.") }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                source = uri
                sourceSize = try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { if (it.length >= 0) it.length else null }
                } catch (_: Exception) { null }
                output = null
                savedUri = null
                status = "Foto siap diproses."
            }
        }

        fun processPhoto() {
            val uri = source ?: return
            // Snapshot Compose state on the UI thread before starting background work.
            val targetBytes = targetKb.roundToInt().toLong() * 1024L
            val initialQuality = quality.roundToInt().coerceIn(30, 100)
            val requestedWidth = maxWidth.roundToInt().coerceIn(320, 4096)
            val selectedFormat = format
            busy = true
            output = null
            savedUri = null
            status = "Sedang memproses foto…"

            Thread {
                var decoded: Bitmap? = null
                var current: Bitmap? = null
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        ?: throw IOException("Foto tidak bisa dibaca.")
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Format gambar tidak dikenali.")

                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= requestedWidth * 2 &&
                        bounds.outHeight / (sample * 2) >= 320) sample *= 2
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    decoded = contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: throw IOException("Gagal membuka gambar.")

                    val original = decoded!!
                    val scale = minOf(1f, requestedWidth.toFloat() / original.width.toFloat())
                    val outWidth = max(1, (original.width * scale).roundToInt())
                    val outHeight = max(1, (original.height * scale).roundToInt())
                    current = if (outWidth != original.width || outHeight != original.height)
                        Bitmap.createScaledBitmap(original, outWidth, outHeight, true) else original

                    var best: ByteArray? = null
                    var bestUnderSize = false
                    var q = initialQuality
                    var attempt = 0
                    while (attempt < 30) {
                        attempt++
                        val stream = ByteArrayOutputStream()
                        val success = current!!.compress(
                            selectedFormat.bitmapFormat,
                            if (selectedFormat == ImageFormat.PNG) 100 else q,
                            stream
                        )
                        if (!success) throw IOException("Gagal membuat format keluaran.")
                        val candidate = stream.toByteArray()

                        if (candidate.size.toLong() <= targetBytes) {
                            if (!bestUnderSize || candidate.size > (best?.size ?: -1)) best = candidate
                            bestUnderSize = true
                            if (selectedFormat == ImageFormat.PNG || q <= 30) break
                            q = max(30, q - 8)
                            // Don't shrink further after reaching the target; keep the best in-budget candidate.
                            continue
                        } else if (!bestUnderSize) {
                            if (best == null || candidate.size < best!!.size) best = candidate
                        }

                        if (selectedFormat != ImageFormat.PNG && q > 30) {
                            q = max(30, q - 10)
                        } else {
                            val bitmap = current!!
                            if (bitmap.width <= 320 || bitmap.height <= 320) break
                            val factor = if (candidate.size.toLong() > targetBytes * 2L) 0.72f else 0.86f
                            val scaled = Bitmap.createScaledBitmap(
                                bitmap,
                                max(1, (bitmap.width * factor).roundToInt()),
                                max(1, (bitmap.height * factor).roundToInt()),
                                true
                            )
                            if (bitmap !== decoded && !bitmap.isRecycled) bitmap.recycle()
                            current = scaled
                            q = initialQuality
                        }
                    }

                    val result = best ?: throw IOException("Tidak berhasil menghasilkan gambar.")
                    val dimensions = current!!.width.toString() + " × " + current!!.height + " px"
                    val message = if (result.size.toLong() <= targetBytes)
                        "Selesai: target ukuran tercapai." else
                        "Selesai, tetapi target belum tercapai. Coba target lebih besar atau JPG/WebP."
                    runOnUiThread {
                        output = result
                        savedUri = null
                        busy = false
                        status = message + " Resolusi hasil: " + dimensions + "."
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        busy = false
                        status = "Gagal memproses foto: " + (e.message ?: "coba gambar lain")
                    }
                } finally {
                    if (current != null && current !== decoded && !current!!.isRecycled) current!!.recycle()
                    if (decoded != null && !decoded!!.isRecycled) decoded!!.recycle()
                }
            }.start()
        }

        fun savePhoto() {
            val bytes = output ?: return
            val selectedFormat = format
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Tank171_PhotoLite_" + System.currentTimeMillis() + "." + selectedFormat.extension)
                    put(MediaStore.Images.Media.MIME_TYPE, selectedFormat.mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tank171PhotoLite")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Tidak bisa membuat file di galeri.")
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("Tidak bisa menulis hasil gambar.")
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
                savedUri = uri
                status = "Tersimpan di Pictures/Tank171PhotoLite."
                Toast.makeText(this@MainActivity, "Foto berhasil disimpan", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                status = "Gagal menyimpan: " + (e.message ?: "penyimpanan bermasalah")
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(Cyan, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = Navy, modifier = Modifier.size(28.dp))
                    }
                    Column {
                        Text("Tank171 PhotoLite", fontSize = 23.sp, color = Color.White)
                        Text("Kompres • konversi • ubah resolusi", color = Muted, fontSize = 13.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. PILIH FOTO", color = Cyan, fontSize = 12.sp)
                        Text(if (source == null) "Belum ada foto dipilih" else "Foto terpilih", fontSize = 17.sp)
                        Text(sourceSize?.let { "Ukuran asli: " + formatBytes(it) } ?: "JPG, PNG, WebP, dan format yang didukung Android", color = Muted, fontSize = 13.sp)
                        Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pilih dari galeri")
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. ATUR HASIL", color = Cyan, fontSize = 12.sp)
                        Text("Format keluaran", fontSize = 15.sp)
                        ImageFormat.values().forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = format == option,
                                    onClick = { format = option; output = null; savedUri = null },
                                    enabled = !busy
                                )
                                Text(option.label, color = Color.White)
                            }
                        }
                        Text("Target ukuran: " + targetKb.roundToInt() + " KB", fontSize = 17.sp)
                        Slider(value = targetKb, onValueChange = { targetKb = it }, valueRange = 50f..2048f, steps = 18, enabled = !busy)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("50 KB", color = Muted, fontSize = 11.sp)
                            Text("2 MB", color = Muted, fontSize = 11.sp)
                        }
                        Text("Kualitas JPEG/WebP: " + quality.roundToInt() + "%", fontSize = 15.sp)
                        Slider(value = quality, onValueChange = { quality = it }, valueRange = 30f..100f, enabled = !busy && format != ImageFormat.PNG)
                        Text("Lebar maksimum: " + maxWidth.roundToInt() + " px", fontSize = 15.sp)
                        Slider(value = maxWidth, onValueChange = { maxWidth = it }, valueRange = 320f..2560f, steps = 13, enabled = !busy)
                        Text("Rasio gambar dipertahankan otomatis.", color = Muted, fontSize = 12.sp)
                        Button(onClick = { processPhoto() }, enabled = source != null && !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Proses gambar")
                        }
                    }
                }
                if (output != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("HASIL GAMBAR", color = Cyan, fontSize = 12.sp)
                            Text(formatBytes(output!!.size.toLong()), fontSize = 25.sp)
                            val before = sourceSize
                            if (before != null && before > 0) {
                                val reduction = 100.0 * (before - output!!.size) / before
                                Text("Perubahan ukuran: " + DecimalFormat("0.0").format(reduction) + "%", color = Muted)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { savePhoto() }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.SaveAlt, contentDescription = null)
                                    Spacer(Modifier.width(6.dp)); Text("Simpan")
                                }
                                OutlinedButton(onClick = {
                                    val uri = savedUri
                                    if (uri != null) {
                                        val mime = format.mime
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = mime
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        startActivity(Intent.createChooser(intent, "Bagikan gambar"))
                                    } else status = "Simpan hasil dulu sebelum membagikan."
                                }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                    Spacer(Modifier.width(6.dp)); Text("Bagikan")
                                }
                            }
                        }
                    }
                }
                Text(status, color = Muted, fontSize = 13.sp)
                Text("Pemrosesan gambar dilakukan di perangkat • Tank171", color = Muted, fontSize = 11.sp)
            }
        }
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes < 1024) "$bytes B"
        else if (bytes < 1024 * 1024) DecimalFormat("0.0").format(bytes / 1024.0) + " KB"
        else DecimalFormat("0.00").format(bytes / (1024.0 * 1024.0)) + " MB"
}
