package com.tank171.photolite

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.delay
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
import java.io.File
import java.text.DecimalFormat
import kotlin.math.max
import kotlin.math.roundToInt

private val Navy = Color(0xFF0A1020)
private val Panel = Color(0xFF151F33)
private val Cyan = Color(0xFF39D7F2)
private val Muted = Color(0xFF9AAAC1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 16, 32)
        window.navigationBarColor = android.graphics.Color.rgb(10, 16, 32)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Cyan, background = Navy, surface = Panel,
                onPrimary = Navy, onBackground = Color.White, onSurface = Color.White
            )) { PhotoLiteScreen() }
        }
    }

    @Composable
    private fun PhotoLiteScreen() {
        var showIntro by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(2000)
            showIntro = false
        }
        var source by remember { mutableStateOf<Uri?>(null) }
        var sourceSize by remember { mutableStateOf<Long?>(null) }
        var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewDimensions by remember { mutableStateOf<String?>(null) }
        var targetKb by remember { mutableFloatStateOf(471f) }
        var output by remember { mutableStateOf<ByteArray?>(null) }
        var savedUri by remember { mutableStateOf<Uri?>(null) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Pilih foto untuk mulai mengolah gambar.") }
        var pendingSaveAfterPermission by remember { mutableStateOf(false) }
        var pendingShareAfterPermission by remember { mutableStateOf(false) }

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
                        var originalWidth = 0
                        var originalHeight = 0
                        val thumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val imageSource = ImageDecoder.createSource(contentResolver, uri)
                            ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                                originalWidth = info.size.width
                                originalHeight = info.size.height
                                val largest = max(originalWidth, originalHeight)
                                if (largest > 600) {
                                    val ratio = 600f / largest.toFloat()
                                    decoder.setTargetSize(
                                        max(1, (originalWidth * ratio).roundToInt()),
                                        max(1, (originalHeight * ratio).roundToInt())
                                    )
                                }
                                decoder.isMutableRequired = false
                            }
                        } else {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                            originalWidth = bounds.outWidth
                            originalHeight = bounds.outHeight
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / 600)
                            }
                            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                        }
                        runOnUiThread {
                            previewBitmap = thumb
                            if (originalWidth > 0 && originalHeight > 0) {
                                previewDimensions = "${originalWidth} × ${originalHeight} px"
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
            val initialQuality = 85
            val requestedWidth = 4096
            busy = true
            output = null
            savedUri = null
            status = "Sedang memproses foto…"

            Thread {
                var decoded: Bitmap? = null
                var current: Bitmap? = null
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // ImageDecoder supports the image formats recognized by the Android device,
                        // including newer formats on Android versions that provide their decoders.
                        val imageSource = ImageDecoder.createSource(contentResolver, uri)
                        decoded = ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                            val sourceWidth = info.size.width
                            val sourceHeight = info.size.height
                            if (sourceWidth <= 0 || sourceHeight <= 0) {
                                throw IOException("Format gambar tidak dikenali atau foto tidak dapat dibaca.")
                            }
                            val largest = max(sourceWidth, sourceHeight)
                            if (largest > requestedWidth) {
                                val ratio = requestedWidth.toFloat() / largest.toFloat()
                                decoder.setTargetSize(
                                    max(1, (sourceWidth * ratio).roundToInt()),
                                    max(1, (sourceHeight * ratio).roundToInt())
                                )
                            }
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                        }
                    } else {
                        // Older Android versions use BitmapFactory for formats they support.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        val boundsStream = contentResolver.openInputStream(uri)
                            ?: throw IOException("Tidak bisa membuka foto dari galeri.")
                        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            throw IOException("Format gambar ini tidak didukung Android versi lama.")
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
                    }

                    val original = decoded!!
                    val scale = minOf(1f, requestedWidth.toFloat() / original.width.toFloat())
                    val outWidth = max(1, (original.width * scale).roundToInt())
                    val outHeight = max(1, (original.height * scale).roundToInt())
                    current = if (outWidth != original.width || outHeight != original.height)
                        Bitmap.createScaledBitmap(original, outWidth, outHeight, true) else original

                    var best: ByteArray? = null
                    var bestWidth = current!!.width
                    var bestHeight = current!!.height
                    var q = initialQuality
                    var attempt = 0
                    while (attempt < 40) {
                        attempt++
                        val bitmap = current!!
                        val stream = ByteArrayOutputStream()
                        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, q, stream)
                        if (!success) throw IOException("Gagal mengompres foto.")
                        val candidate = stream.toByteArray()

                        // The target KB is a maximum size; JPEG quality is adjusted internally.
                        if (candidate.size.toLong() <= targetBytes) {
                            best = candidate
                            bestWidth = bitmap.width
                            bestHeight = bitmap.height
                            break
                        }

                        if (best == null || candidate.size < best!!.size) {
                            best = candidate
                            bestWidth = bitmap.width
                            bestHeight = bitmap.height
                        }

                        if (q > 30) {
                            q = max(30, q - 10)
                            continue
                        }

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
                        best = null
                        q = initialQuality
                    }

                    val result = best ?: throw IOException("Tidak berhasil menghasilkan gambar.")
                    val dimensions = bestWidth.toString() + " × " + bestHeight + " px"
                    val message = if (result.size.toLong() <= targetBytes)
                        "Selesai: ukuran di bawah batas target." else
                        "Selesai, tetapi batas target belum tercapai pada kualitas/resolusi minimum."
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
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingShareAfterPermission = shareAfterSave
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
            try {
                val displayName = "PhotoLite_171_" + System.currentTimeMillis() + ".jpg"
                val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoLite171")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val inserted = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Android tidak bisa membuat file di galeri. Coba ruang penyimpanan lain.")
                    try {
                        contentResolver.openOutputStream(inserted, "w")?.use { it.write(bytes) }
                            ?: throw IOException("Android tidak bisa menulis hasil gambar.")
                        val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                        contentResolver.update(inserted, ready, null, null)
                        inserted
                    } catch (e: Exception) {
                        contentResolver.delete(inserted, null, null)
                        throw e
                    }
                } else {
                    // Android 9 and older: write to the app's Pictures folder and register
                    // the exact filename and MIME type in MediaStore.
                    val picturesDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "PhotoLite171"
                    )
                    if (!picturesDir.exists() && !picturesDir.mkdirs()) {
                        throw IOException("Tidak bisa membuat folder Pictures/PhotoLite171.")
                    }
                    val file = File(picturesDir, displayName)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.TITLE, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.DATA, file.absolutePath)
                    }
                    val inserted = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Android tidak bisa mendaftarkan file di galeri.")
                    try {
                        contentResolver.openOutputStream(inserted, "w")?.use { it.write(bytes) }
                            ?: throw IOException("Android tidak bisa menulis hasil gambar.")
                        inserted
                    } catch (e: Exception) {
                        contentResolver.delete(inserted, null, null)
                        if (file.exists()) file.delete()
                        throw e
                    }
                }
                savedUri = uri
                status = "Foto berhasil disimpan. Nama file: " + displayName + ". Lokasi: Galeri > Pictures/PhotoLite171."
                Toast.makeText(this@MainActivity, "Foto berhasil disimpan", Toast.LENGTH_SHORT).show()
                if (shareAfterSave) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
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
                val shouldShare = pendingShareAfterPermission
                pendingShareAfterPermission = false
                savePhoto(shareAfterSave = shouldShare)
            }
        }

        if (showIntro) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF07101F), Color(0xFF102744), Color(0xFF050914)))
                ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(28.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(142.dp),
                        shape = RoundedCornerShape(38.dp),
                        color = Color(0xFF10243B),
                        border = BorderStroke(1.5.dp, Cyan),
                        shadowElevation = 22.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_photolite_171),
                                contentDescription = "Logo PicLite 171",
                                modifier = Modifier.size(112.dp)
                            )
                        }
                    }
                    Text("PicLite 171", fontSize = 34.sp, color = Color.White)
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Cyan, strokeWidth = 2.dp)
                    Text("Siap dalam sekejap", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
        Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.ic_photolite_171),
                        contentDescription = "Logo PicLite 171",
                        modifier = Modifier.size(56.dp)
                    )
                    Column {
                        Text("PicLite 171", fontSize = 23.sp, color = Color.White)
                        Text("Kompres foto dengan mudah", color = Muted, fontSize = 13.sp)
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
                            Text(sourceSize?.let { "Ukuran asli: " + formatBytes(it) } ?: "Pilih gambar dengan format yang didukung Android", color = Muted, fontSize = 13.sp)
                        }
                        Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pilih foto")
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. KOMPRESI", color = Cyan, fontSize = 13.sp)
                        Text("Format hasil: JPG", fontSize = 15.sp)
                        Text("Batas ukuran: " + targetKb.roundToInt() + " KB", fontSize = 17.sp)
                        Slider(value = targetKb, onValueChange = { targetKb = it }, valueRange = 50f..5120f, enabled = !busy)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("50 KB", color = Muted, fontSize = 11.sp)
                            Text("5 MB", color = Muted, fontSize = 11.sp)
                        }
                        
                        Button(onClick = { processPhoto() }, enabled = source != null && !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Kompres foto")
                        }
                    }
                }
                if (output != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("HASIL GAMBAR", color = Cyan, fontSize = 12.sp)
                            val resultBytes = output!!
                            val resultBitmap = remember(resultBytes) {
                                BitmapFactory.decodeByteArray(resultBytes, 0, resultBytes.size)
                            }
                            if (resultBitmap != null) {
                                Image(
                                    bitmap = resultBitmap.asImageBitmap(),
                                    contentDescription = "Pratinjau hasil foto",
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                    contentScale = ContentScale.Fit
                                )
                                Text("Resolusi hasil: ${resultBitmap.width} × ${resultBitmap.height} px", color = Muted, fontSize = 13.sp)
                            }
                            Text("Format hasil: JPG", color = Color.White, fontSize = 15.sp)
                            Text("Ukuran hasil: " + formatBytes(output!!.size.toLong()), fontSize = 22.sp)
                            val before = sourceSize
                            if (before != null && before > 0) {
                                val reduction = 100.0 * (before - output!!.size) / before
                                Text("Ukuran asli: ${formatBytes(before)}", color = Muted)
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
                Text("Foto diproses di perangkat • PicLite 171", color = Muted, fontSize = 11.sp)
            }
        }
        }
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes < 1024) "$bytes B"
        else if (bytes < 1024 * 1024) DecimalFormat("0.0").format(bytes / 1024.0) + " KB"
        else DecimalFormat("0.00").format(bytes / (1024.0 * 1024.0)) + " MB"
}
