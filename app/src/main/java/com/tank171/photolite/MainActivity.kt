package com.tank171.photolite

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.res.painterResource
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
    WEBP("WebP", "image/webp", "webp", Bitmap.CompressFormat.WEBP)
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
        var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewDimensions by remember { mutableStateOf<String?>(null) }
        var targetKb by remember { mutableFloatStateOf(471f) }
        var quality by remember { mutableFloatStateOf(82f) }
        var maxWidth by remember { mutableFloatStateOf(1600f) }
        var format by remember { mutableStateOf(ImageFormat.JPEG) }
        var output by remember { mutableStateOf<ByteArray?>(null) }
        var savedUri by remember { mutableStateOf<Uri?>(null) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Pilih foto untuk mulai mengolah gambar.") }
        var pendingSaveAfterPermission by remember { mutableStateOf(false) }

        val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingSaveAfterPermission = true
            else status = "Izin penyimpanan ditolak. Izinkan akses agar hasil bisa disimpan."
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                source = uri
                previewBitmap = null
                previewDimensions = null
                Thread {
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / 600)
                        }
                        val thumb = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                        runOnUiThread {
                            previewBitmap = thumb
                            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                                previewDimensions = "${bounds.outWidth} × ${bounds.outHeight} px"
                            }
                        }
                    } catch (_: Exception) { }
                }.start()
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
                    val boundsStream = contentResolver.openInputStream(uri)
                        ?: throw IOException("Tidak bisa membuka foto dari galeri.")
                    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
                    // With inJustDecodeBounds=true, decodeStream normally returns null by design.
                    // Validate the dimensions instead of treating that null as a decode failure.
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        throw IOException("Format gambar tidak dikenali atau foto tidak dapat dibaca.")
                    }

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
                            // Target is a maximum. If far below it, raise quality to get closer.
                            if (selectedFormat == ImageFormat.PNG || q >= 100) break
                            q = minOf(100, q + 5)
                            continue
                        } else if (!bestUnderSize) {
                            if (best == null || candidate.size < best!!.size) best = candidate
                        } else {
                            // Keep the best in-budget candidate rather than degrading it.
                            break
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

        fun savePhoto(shareAfterSave: Boolean = false) {
            val bytes = output ?: return
            val selectedFormat = format
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "PhotoLite_171_" + System.currentTimeMillis() + "." + selectedFormat.extension)
                    put(MediaStore.Images.Media.MIME_TYPE, selectedFormat.mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoLite171")
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
                status = "Tersimpan di Pictures/PhotoLite171."
                Toast.makeText(this@MainActivity, "Foto berhasil disimpan", Toast.LENGTH_SHORT).show()
                if (shareAfterSave) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = selectedFormat.mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Bagikan gambar"))
                }
            } catch (e: Exception) {
                status = "Gagal menyimpan: " + (e.message ?: "penyimpanan bermasalah")
            }
        }

        LaunchedEffect(pendingSaveAfterPermission) {
            if (pendingSaveAfterPermission) {
                pendingSaveAfterPermission = false
                savePhoto()
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.ic_photolite_171),
                        contentDescription = "Logo PhotoLite 171",
                        modifier = Modifier.size(56.dp)
                    )
                    Column {
                        Text("PhotoLite 171", fontSize = 23.sp, color = Color.White)
                        Text("Kompres • konversi • ubah resolusi", color = Muted, fontSize = 13.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. PILIH FOTO", color = Cyan, fontSize = 12.sp)
                        Text(if (source == null) "Belum ada foto dipilih" else "Foto terpilih", fontSize = 17.sp)
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap!!.asImageBitmap(),
                                contentDescription = "Pratinjau foto terpilih",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).aspectRatio(
                                    previewBitmap!!.width.toFloat() / previewBitmap!!.height.toFloat(),
                                    matchHeightConstraintsFirst = false
                                ),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                listOfNotNull(sourceSize?.let { formatBytes(it) }, previewDimensions).joinToString(" • "),
                                color = Muted, fontSize = 13.sp
                            )
                        } else {
                            Text(sourceSize?.let { "Ukuran asli: " + formatBytes(it) } ?: "Pilih JPG, PNG, WebP, atau format yang didukung Android", color = Muted, fontSize = 13.sp)
                        }
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
                        Slider(value = targetKb, onValueChange = { targetKb = it }, valueRange = 50f..5120f, enabled = !busy)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("50 KB", color = Muted, fontSize = 11.sp)
                            Text("5 MB", color = Muted, fontSize = 11.sp)
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
                                OutlinedButton(onClick = { savePhoto(shareAfterSave = true) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                    Spacer(Modifier.width(6.dp)); Text("Bagikan")
                                }
                            }
                        }
                    }
                }
                Text(status, color = Muted, fontSize = 13.sp)
                Text("Pemrosesan gambar dilakukan di perangkat • PhotoLite 171", color = Muted, fontSize = 11.sp)
            }
        }
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes < 1024) "$bytes B"
        else if (bytes < 1024 * 1024) DecimalFormat("0.0").format(bytes / 1024.0) + " KB"
        else DecimalFormat("0.00").format(bytes / (1024.0 * 1024.0)) + " MB"
}
