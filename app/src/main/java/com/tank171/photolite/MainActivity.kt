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
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val Navy = Color(0xFF0A1020)
private val Panel = Color(0xFF151F33)
private val Cyan = Color(0xFF39D7F2)
private val Muted = Color(0xFF9AAAC1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan, background = Navy, surface = Panel,
                    onPrimary = Navy, onBackground = Color.White, onSurface = Color.White
                )
            ) { PhotoLiteScreen() }
        }
    }

    @Composable
    private fun PhotoLiteScreen() {
        var source by remember { mutableStateOf<Uri?>(null) }
        var sourceSize by remember { mutableStateOf<Long?>(null) }
        var targetKb by remember { mutableFloatStateOf(200f) }
        var quality by remember { mutableFloatStateOf(82f) }
        var output by remember { mutableStateOf<ByteArray?>(null) }
        var savedUri by remember { mutableStateOf<Uri?>(null) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Pilih foto untuk mulai mengompres.") }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                source = uri
                sourceSize = try { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } } catch (_: Exception) { null }
                output = null
                savedUri = null
                status = "Foto siap diproses."
            }
        }

        fun compressPhoto() {
            val uri = source ?: return
            busy = true
            status = "Sedang mengompres foto…"
            Thread {
                try {
                    val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: throw IOException("Foto tidak bisa dibuka.")
                    val targetBytes = (targetKb * 1024).toInt()
                    var current = bitmap
                    var best = byteArrayOf()
                    var q = quality.toInt().coerceIn(20, 95)
                    for (pass in 0 until 9) {
                        val stream = ByteArrayOutputStream()
                        current.compress(Bitmap.CompressFormat.JPEG, q, stream)
                        val bytes = stream.toByteArray()
                        best = bytes
                        if (bytes.size <= targetBytes) break
                        if (q > 25) q = (q - 12).coerceAtLeast(25)
                        else {
                            val scaled = Bitmap.createScaledBitmap(current, (current.width * 0.82f).toInt().coerceAtLeast(1),
                                (current.height * 0.82f).toInt().coerceAtLeast(1), true)
                            if (current !== bitmap) current.recycle()
                            current = scaled
                        }
                    }
                    if (current !== bitmap) current.recycle()
                    bitmap.recycle()
                    runOnUiThread {
                        output = best
                        savedUri = null
                        busy = false
                        status = if (best.size <= targetBytes) "Selesai — ukuran target tercapai." else "Selesai — ukuran terkecil yang dicoba; target mungkin belum tercapai."
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        busy = false
                        status = "Gagal memproses foto: ${e.message ?: "coba foto lain"}"
                    }
                }
            }.start()
        }

        fun savePhoto() {
            val bytes = output ?: return
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Tank171_PhotoLite_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tank171PhotoLite")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Tidak bisa membuat file.")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IOException("Tidak bisa menyimpan file.")
                savedUri = uri
                status = "Foto tersimpan di Pictures/Tank171PhotoLite."
                Toast.makeText(this@MainActivity, "Foto berhasil disimpan", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                status = "Gagal menyimpan: ${e.message}"
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
            Column(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(Cyan, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = Navy, modifier = Modifier.size(28.dp))
                    }
                    Column {
                        Text("Tank171 PhotoLite", fontSize = 23.sp, color = Color.White)
                        Text("Kompres foto, tetap praktis.", color = Muted, fontSize = 13.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. PILIH FOTO", color = Cyan, fontSize = 12.sp)
                        Text(if (source == null) "Belum ada foto dipilih" else "Foto terpilih", fontSize = 17.sp)
                        Text(sourceSize?.let { "Ukuran asli: ${formatBytes(it)}" } ?: "JPG, PNG, dan format gambar yang didukung perangkat", color = Muted, fontSize = 13.sp)
                        Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pilih dari galeri")
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. ATUR UKURAN", color = Cyan, fontSize = 12.sp)
                        Text("Target: ${targetKb.toInt()} KB", fontSize = 18.sp)
                        Slider(value = targetKb, onValueChange = { targetKb = it }, valueRange = 100f..1024f,
                            steps = 8, enabled = !busy)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("100 KB", color = Muted, fontSize = 11.sp)
                            Text("1 MB", color = Muted, fontSize = 11.sp)
                        }
                        Text("Kualitas JPEG: ${quality.toInt()}%", fontSize = 15.sp)
                        Slider(value = quality, onValueChange = { quality = it }, valueRange = 30f..95f, enabled = !busy)
                        Button(onClick = { compressPhoto() }, enabled = source != null && !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Kompres foto")
                        }
                    }
                }
                if (output != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("HASIL KOMPRES", color = Cyan, fontSize = 12.sp)
                            Text(formatBytes(output!!.size.toLong()), fontSize = 25.sp)
                            val before = sourceSize
                            if (before != null && before > 0) {
                                val reduction = (100.0 * (before - output!!.size) / before)
                                Text("Perubahan ukuran: ${DecimalFormat("0.0").format(reduction)}%", color = Muted)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { savePhoto() }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.SaveAlt, contentDescription = null)
                                    Spacer(Modifier.width(6.dp)); Text("Simpan")
                                }
                                OutlinedButton(onClick = {
                                    val uri = savedUri
                                    if (uri != null) {
                                        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                        startActivity(Intent.createChooser(intent, "Bagikan foto"))
                                    } else status = "Simpan foto dulu sebelum membagikan."
                                }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                    Spacer(Modifier.width(6.dp)); Text("Bagikan")
                                }
                            }
                        }
                    }
                }
                Text(status, color = Muted, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("Pemrosesan dilakukan di perangkat • Tank171", color = Muted, fontSize = 11.sp)
            }
        }
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "${DecimalFormat("0.0").format(bytes / 1024.0)} KB"
        else "${DecimalFormat("0.00").format(bytes / (1024.0 * 1024.0))} MB"
}
