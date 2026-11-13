package com.vedanand.ncdlistocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Terminal theme ----------
val TERM_BG = Color(0xFF000000)
val TERM_PANEL = Color(0xFF07110F)
val TERM_TEAL = Color(0xFF00E5CC)
val TERM_BLUE = Color(0xFF2196F3)
val TERM_DIM = Color(0xFF0E2B27)
val TERM_TEXT = Color(0xFFB2F5EC)
val TERM_WARN = Color(0xFFFFC107)

data class Entry(
    var name: String,
    var age: String
)

data class OcrCell(
    val text: String,
    val rect: Rect,
    val blockIndex: Int,
    val lineIndex: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalApp() }
    }
}

@Composable
fun TerminalApp() {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cells by remember { mutableStateOf<List<OcrCell>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<Pair<Int, Int>>()) }
    var entries by remember { mutableStateOf(listOf<Entry>()) }
    var status by remember { mutableStateOf("$ SYSTEM READY") }
    var loading by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var cropMode by remember { mutableStateOf(false) }
    var cropStart by remember { mutableStateOf<Offset?>(null) }
    var cropEnd by remember { mutableStateOf<Offset?>(null) }
    var showGrid by remember { mutableStateOf(false) }

    fun resetForNewImage() { selected = emptySet(); entries = emptyList(); cropMode = false; showGrid = false }

    fun ocrBitmap(bmp: Bitmap) {
        loading = true
        val image = InputImage.fromBitmap(bmp, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val list = mutableListOf<OcrCell>()
                visionText.textBlocks.forEachIndexed { bi, block ->
                    block.lines.forEachIndexed { li, line ->
                        line.boundingBox?.let { list.add(OcrCell(line.text, it, bi, li)) }
                    }
                }
                cells = list
                selected = emptySet()
                loading = false
                status = "$ OCR DONE: " + list.size + " CELLS. Tap cell = select, long-press = full column"
            }
            .addOnFailureListener {
                cells = emptyList(); loading = false
                status = "$ OCR FAILED"
            }
    }

    // --- Gallery picker ---
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) {
        resetForNewImage()
        val bmp = loadBitmapWithExif(context, uri)
        if (bmp != null) { bitmap = bmp; ocrBitmap(bmp) } else status = "$ IMAGE LOAD FAILED"
    } }

    // --- Camera capture ---
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val u = photoUri
        if (ok && u != null) {
            resetForNewImage()
            val bmp = loadBitmapWithExif(context, u)
            if (bmp != null) { bitmap = bmp; ocrBitmap(bmp) } else status = "$ IMAGE LOAD FAILED"
        }
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        photoUri = uri
        cameraLauncher.launch(uri)
    }

    // --- CSV / text file import ---
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)!!
                    .bufferedReader().use { it.readText() }
                entries = parseCsv(text)
                status = "$ CSV IMPORTED: " + entries.size + " ROWS"
            } catch (e: Exception) {
                status = "$ CSV READ FAILED"
            }
        }
    }

    fun rotate90() {
        val b = bitmap ?: return
        resetForNewImage()
        val rotated = rotateBitmap(b, 90f)
        bitmap = rotated
        ocrBitmap(rotated)
    }

    fun autoCrop() {
        val b = bitmap ?: return
        if (cells.isEmpty()) { status = "$ AUTO CROP: pehla OCR joiye"; return }
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE
        var right = 0; var bottom = 0
        cells.forEach {
            left = minOf(left, it.rect.left); top = minOf(top, it.rect.top)
            right = maxOf(right, it.rect.right); bottom = maxOf(bottom, it.rect.bottom)
        }
        val mx = ((right - left) * 0.05f).toInt().coerceAtLeast(10)
        val my = ((bottom - top) * 0.05f).toInt().coerceAtLeast(10)
        val cl = (left - mx).coerceAtLeast(0); val ct = (top - my).coerceAtLeast(0)
        val cr = (right + mx).coerceAtMost(b.width); val cb = (bottom + my).coerceAtMost(b.height)
        if (cr - cl < 20 || cb - ct < 20) { status = "$ AUTO CROP FAILED"; return }
        resetForNewImage()
        val cropped = Bitmap.createBitmap(b, cl, ct, cr - cl, cb - ct)
        bitmap = cropped
        ocrBitmap(cropped)
        status = "$ AUTO CROPPED + re-OCR"
    }

    // --- Scanning animation ---
    val scanTransition = rememberInfiniteTransition(label = "scan")
    val scanY by scanTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "scanY"
    )

    val clipboard = LocalClipboardManager.current

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = TERM_BG, surface = TERM_PANEL,
            primary = TERM_TEAL, onPrimary = Color.Black,
            secondary = TERM_BLUE, onBackground = TERM_TEXT, onSurface = TERM_TEXT
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = TERM_BG) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    "> NCD_LIST_OCR v2.3_",
                    color = TERM_TEAL, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp
                )
                Text(
                    status,
                    color = TERM_TEAL.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermButton("CAMERA", Modifier.weight(1f)) { launchCamera() }
                    TermButton("GALLERY", Modifier.weight(1f)) {
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    TermButton("CSV", Modifier.weight(1f)) {
                        filePicker.launch(arrayOf("text/csv", "text/plain", "text/comma-separated-values", "application/csv"))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermButton("⟳ ROTATE", Modifier.weight(1f), enabled = bitmap != null && !loading) { rotate90() }
                    TermButton(if (cropMode) "✂ CROPPING..." else "✂ CROP", Modifier.weight(1f), enabled = bitmap != null && !loading) {
                        cropMode = !cropMode; cropStart = null; cropEnd = null
                        status = if (cropMode) "$ CROP: photo par drag karo, pachi APPLY dabao" else "$ CROP CANCELLED"
                    }
                    TermButton("▣ AUTOCROP", Modifier.weight(1f), enabled = bitmap != null && !loading) { autoCrop() }
                    TermButton(if (showGrid) "▦ PHOTO" else "▦ GRID", Modifier.weight(1f), enabled = cells.isNotEmpty()) { showGrid = !showGrid }
                }
                Spacer(Modifier.height(8.dp))

                val bmp = bitmap
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(TERM_PANEL, RoundedCornerShape(8.dp))
                        .border(1.dp, TERM_DIM, RoundedCornerShape(8.dp))
                ) {
                    if (bmp == null) {
                        Text(
                            "$ AWAITING IMAGE INPUT...\n\n(camera / gallery / csv)",
                            color = TERM_TEAL.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center
                        )
                    } else if (showGrid) {
                        // ---- Excel-like grid viewer ----
                        val rows = gridRows(cells)
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(4.dp)
                        ) {
                            rows.forEachIndexed { ri, row ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        (ri + 1).toString(),
                                        color = TERM_DIM, fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .width(28.dp)
                                            .padding(4.dp)
                                    )
                                    row.forEach { cell ->
                                        val key = cell.blockIndex to cell.lineIndex
                                        val isSel = key in selected
                                        Text(
                                            cell.text,
                                            color = if (isSel) Color.Black else TERM_TEXT,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .padding(1.dp)
                                                .background(
                                                    if (isSel) TERM_TEAL else TERM_BG,
                                                    RoundedCornerShape(3.dp)
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isSel) TERM_TEAL else TERM_DIM,
                                                    RoundedCornerShape(3.dp)
                                                )
                                                .clickable {
                                                    selected = if (isSel) selected - key else selected + key
                                                }
                                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        var boxSize by remember { mutableStateOf(IntSize.Zero) }
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { boxSize = it }
                        )
                        val scale = minOf(
                            boxSize.width.toFloat() / bmp.width,
                            boxSize.height.toFloat() / bmp.height
                        )
                        val offX = (boxSize.width - bmp.width * scale) / 2f
                        val offY = (boxSize.height - bmp.height * scale) / 2f

                        if (cropMode) {
                            // ---- Manual crop: drag rectangle ----
                            Canvas(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(cropMode) {
                                        detectDragGestures(
                                            onDragStart = { cropStart = it; cropEnd = it },
                                            onDragEnd = { },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                cropEnd = change.position
                                            }
                                        )
                                    }
                            ) {
                                val s = cropStart; val e2 = cropEnd
                                if (s != null && e2 != null) {
                                    val r = androidx.compose.ui.geometry.Rect(
                                        minOf(s.x, e2.x), minOf(s.y, e2.y),
                                        maxOf(s.x, e2.x), maxOf(s.y, e2.y)
                                    )
                                    drawRect(TERM_TEAL.copy(alpha = 0.15f), r.topLeft, r.size)
                                    drawRect(TERM_TEAL, r.topLeft, r.size, style = Stroke(width = 3f))
                                }
                            }
                            Row(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TermButton("✓ APPLY CROP") {
                                    val s = cropStart; val e2 = cropEnd
                                    if (s != null && e2 != null) {
                                        val l = ((minOf(s.x, e2.x) - offX) / scale).toInt().coerceIn(0, bmp.width - 1)
                                        val t = ((minOf(s.y, e2.y) - offY) / scale).toInt().coerceIn(0, bmp.height - 1)
                                        val r = ((maxOf(s.x, e2.x) - offX) / scale).toInt().coerceIn(l + 1, bmp.width)
                                        val b2 = ((maxOf(s.y, e2.y) - offY) / scale).toInt().coerceIn(t + 1, bmp.height)
                                        resetForNewImage()
                                        val cropped = Bitmap.createBitmap(bmp, l, t, r - l, b2 - t)
                                        bitmap = cropped
                                        cropMode = false
                                        ocrBitmap(cropped)
                                        status = "$ CROPPED + re-OCR"
                                    }
                                }
                                TermButton("✗ CANCEL", color = TERM_WARN) {
                                    cropMode = false; cropStart = null; cropEnd = null
                                }
                            }
                        } else {
                            // ---- Excel-style cell selection overlay ----
                            Canvas(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(cells, scale, offX, offY) {
                                        detectTapGestures(
                                            onTap = { tap ->
                                                var hit: OcrCell? = null
                                                for (cell in cells) {
                                                    val r = Rect(
                                                        (cell.rect.left * scale + offX).toInt(),
                                                        (cell.rect.top * scale + offY).toInt(),
                                                        (cell.rect.right * scale + offX).toInt(),
                                                        (cell.rect.bottom * scale + offY).toInt()
                                                    )
                                                    if (r.contains(tap.x.toInt(), tap.y.toInt())) { hit = cell; break }
                                                }
                                                val c = hit ?: return@detectTapGestures
                                                val key = c.blockIndex to c.lineIndex
                                                selected = if (key in selected) selected - key else selected + key
                                                status = "$ SELECTED " + selected.size + " CELLS (long-press = full column)"
                                            },
                                            onLongPress = { tap ->
                                                var hit: OcrCell? = null
                                                for (cell in cells) {
                                                    val r = Rect(
                                                        (cell.rect.left * scale + offX).toInt(),
                                                        (cell.rect.top * scale + offY).toInt(),
                                                        (cell.rect.right * scale + offX).toInt(),
                                                        (cell.rect.bottom * scale + offY).toInt()
                                                    )
                                                    if (r.contains(tap.x.toInt(), tap.y.toInt())) { hit = cell; break }
                                                }
                                                val tapped = hit ?: return@detectTapGestures
                                                val col = cells.filter { c ->
                                                    val overlap = minOf(c.rect.right, tapped.rect.right) -
                                                            maxOf(c.rect.left, tapped.rect.left)
                                                    overlap > 0.4f * minOf(
                                                        c.rect.width().toFloat(),
                                                        tapped.rect.width().toFloat()
                                                    )
                                                }
                                                selected = selected + col.map { it.blockIndex to it.lineIndex }
                                                status = "$ COLUMN ADDED: " + col.size + " cells. Total " + selected.size
                                            }
                                        )
                                    }
                            ) {
                                cells.forEach { cell ->
                                    val topLeft = Offset(
                                        cell.rect.left * scale + offX,
                                        cell.rect.top * scale + offY
                                    )
                                    val sz = Size(
                                        cell.rect.width() * scale,
                                        cell.rect.height() * scale
                                    )
                                    val key = cell.blockIndex to cell.lineIndex
                                    if (key in selected) {
                                        drawRect(TERM_TEAL.copy(alpha = 0.30f), topLeft, sz)
                                        drawRect(TERM_TEAL, topLeft, sz, style = Stroke(width = 4f))
                                    } else {
                                        drawRect(TERM_BLUE.copy(alpha = 0.20f), topLeft, sz, style = Stroke(width = 2f))
                                    }
                                }
                                if (loading) {
                                    val y = scanY * size.height
                                    drawLine(TERM_TEAL, Offset(0f, y), Offset(size.width, y), strokeWidth = 6f)
                                    drawRect(
                                        TERM_TEAL.copy(alpha = 0.08f),
                                        Offset.Zero,
                                        Size(size.width, y)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton("PAIR & ADD (" + selected.size + ")", Modifier.weight(1f), enabled = selected.isNotEmpty()) {
                        val sel = cells.filter { (it.blockIndex to it.lineIndex) in selected }
                        val pairs = pairSelected(sel)
                        entries = entries + pairs
                        selected = emptySet()
                        status = "$ ADDED " + pairs.size + " ENTRIES"
                    }
                    TermButton("AUTO PARSE", Modifier.weight(1f), enabled = cells.isNotEmpty() && !loading) {
                        entries = cells.map { parseCell(it.text) }.filter { it.name.isNotBlank() }
                        status = "$ PARSED " + entries.size + " ENTRIES"
                    }
                }
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("NAME", color = TERM_TEAL, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("AGE", color = TERM_TEAL, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                LazyColumn(Modifier.weight(1f)) {
                    items(entries.size) { i ->
                        val e = entries[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .background(TERM_PANEL, RoundedCornerShape(4.dp))
                                .clickable { editingIndex = i }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "> " + e.name,
                                color = TERM_TEXT, fontFamily = FontFamily.Monospace, fontSize = 14.sp
                            )
                            Text(
                                e.age.ifBlank { "_" },
                                color = TERM_BLUE, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton("COPY", Modifier.weight(1f), enabled = entries.isNotEmpty()) {
                        val text = entries.joinToString("\n") { (it.name + " " + it.age).trim() }
                        clipboard.setText(AnnotatedString(text))
                        status = "$ COPIED " + entries.size + " LINES TO CLIPBOARD"
                    }
                    TermButton("CLEAR", Modifier.weight(1f), enabled = entries.isNotEmpty()) {
                        entries = emptyList(); status = "$ LIST CLEARED"
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "developed by Vin\u2206y Makwana",
                    color = TERM_TEAL.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            editingIndex?.let { idx ->
                if (idx < entries.size) {
                    val e = entries[idx]
                    var name by remember(idx) { mutableStateOf(e.name) }
                    var age by remember(idx) { mutableStateOf(e.age) }
                    AlertDialog(
                        onDismissRequest = { editingIndex = null },
                        containerColor = TERM_PANEL,
                        title = { Text("EDIT ENTRY", color = TERM_TEAL, fontFamily = FontFamily.Monospace) },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = name, onValueChange = { name = it },
                                    label = { Text("NAME", fontFamily = FontFamily.Monospace) },
                                    colors = TextFieldDefaults.colors(
                                        focusedTextColor = TERM_TEXT, unfocusedTextColor = TERM_TEXT,
                                        focusedContainerColor = TERM_BG, unfocusedContainerColor = TERM_BG
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = age, onValueChange = { age = it.filter { c -> c.isDigit() }.take(3) },
                                    label = { Text("AGE", fontFamily = FontFamily.Monospace) },
                                    colors = TextFieldDefaults.colors(
                                        focusedTextColor = TERM_TEXT, unfocusedTextColor = TERM_TEXT,
                                        focusedContainerColor = TERM_BG, unfocusedContainerColor = TERM_BG
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TermButton("SAVE") {
                                entries = entries.toMutableList().also { it[idx] = Entry(name.trim(), age) }
                                editingIndex = null
                            }
                        },
                        dismissButton = {
                            Row {
                                TermButton("DELETE", color = TERM_WARN) {
                                    entries = entries.toMutableList().also { it.removeAt(idx) }
                                    editingIndex = null
                                }
                                Spacer(Modifier.width(8.dp))
                                TermButton("CANCEL") { editingIndex = null }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TermButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = TERM_TEAL, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = TERM_DIM,
            contentColor = color,
            disabledContainerColor = Color(0xFF0A0A0A),
            disabledContentColor = Color(0xFF333333)
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Image helpers ----------

/** Load bitmap with EXIF rotation applied (fixes auto-rotate problem). */
fun loadBitmapWithExif(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    val sample = maxOf(1, maxDim / 2048)
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null
    val degrees = try {
        val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) }
        when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: Exception) { 0f }
    return if (degrees != 0f) rotateBitmap(bmp, degrees) else bmp
}

fun rotateBitmap(bmp: Bitmap, degrees: Float): Bitmap {
    val m = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

// ---------- OCR helpers ----------

/** Cluster OCR cells into visual rows (for grid viewer). */
fun gridRows(cells: List<OcrCell>): List<List<OcrCell>> {
    val sorted = cells.sortedBy { (it.rect.top + it.rect.bottom) / 2 }
    val rows = mutableListOf<MutableList<OcrCell>>()
    sorted.forEach { c ->
        val cy = (c.rect.top + c.rect.bottom) / 2f
        val row = rows.lastOrNull { r ->
            val last = r.last()
            val rc = (last.rect.top + last.rect.bottom) / 2f
            Math.abs(cy - rc) < 0.7f * last.rect.height().coerceAtLeast(1)
        }
        if (row != null) row.add(c) else rows.add(mutableListOf(c))
    }
    return rows.map { it.sortedBy { x -> x.rect.left } }
}

/**
 * Excel-style: from selected cells, detect name column and age column,
 * then pair row-wise. If only one column selected, parse each cell directly.
 */
fun pairSelected(sel: List<OcrCell>): List<Entry> {
    if (sel.isEmpty()) return emptyList()
    // cluster into columns by x-overlap
    val cols = mutableListOf<MutableList<OcrCell>>()
    sel.sortedBy { it.rect.left }.forEach { c ->
        val col = cols.lastOrNull { c0 ->
            val anchor = c0[0]
            val overlap = minOf(c.rect.right, anchor.rect.right) -
                    maxOf(c.rect.left, anchor.rect.left)
            overlap > 0.3f * minOf(c.rect.width().toFloat(), anchor.rect.width().toFloat())
        }
        if (col != null) col.add(c) else cols.add(mutableListOf(c))
    }
    if (cols.size == 1) {
        return cols[0].sortedBy { it.rect.top }
            .map { parseCell(it.text) }
            .filter { it.name.isNotBlank() }
    }
    // name column = most characters; age column = most numeric cells among the rest
    val nameCol = cols.maxBy { it.sumOf { c -> c.text.length } }
    val ageCol = cols.filter { it !== nameCol }
        .maxByOrNull { it.count { c -> c.text.trim().matches(Regex("\\d{2,3}")) } }
        ?: cols.filter { it !== nameCol }.first()
    return pairColumns(nameCol, ageCol)
}

/**
 * Pair NAME column cells with AGE column cells by vertical (row) alignment.
 */
fun pairColumns(nameCol: List<OcrCell>, ageCol: List<OcrCell>): List<Entry> {
    val names = nameCol.sortedBy { it.rect.top }
    val ages = ageCol.sortedBy { it.rect.top }.toMutableList()
    val used = mutableSetOf<Int>()
    return names.mapNotNull { n ->
        val nCenter = (n.rect.top + n.rect.bottom) / 2f
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        ages.forEachIndexed { ai, a ->
            if (ai in used) return@forEachIndexed
            val aCenter = (a.rect.top + a.rect.bottom) / 2f
            val d = Math.abs(aCenter - nCenter)
            if (d < bestDist) { bestDist = d; bestIdx = ai }
        }
        if (bestIdx < 0) return@mapNotNull null
        used.add(bestIdx)
        parseCell(n.text).copy(age = parseCell(ages[bestIdx].text).age)
    }.filter { it.name.isNotBlank() }
}

/**
 * Parse one OCR line/cell into Entry(name, age).
 */
fun parseCell(raw: String): Entry {
    var text = raw.replace("|", " ").trim()
    text = text.replace(Regex("\\b[MFmf]\\b"), " ")
    text = text.replace(Regex("\\b[6-9]\\d{9}\\b"), " ")
    val ageMatch = Regex("\\b(\\d{2,3})\\b").findAll(text)
        .map { it.groupValues[1].toInt() }
        .firstOrNull { it in 18..120 }
    if (ageMatch != null) text = text.replace(Regex("\\b" + ageMatch + "\\b"), " ")
    val name = text.replace(Regex("[^A-Za-z\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()
    return Entry(name, ageMatch?.toString() ?: "")
}

/**
 * CSV import: header-aware.
 */
fun parseCsv(raw: String): List<Entry> {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()

    fun split(line: String): List<String> =
        line.split(Regex(",|\\t|;")).map { it.trim().removeSurrounding("\"") }

    val header = split(lines[0]).map { it.lowercase(Locale.ROOT) }
    val nameIdx = header.indexOfFirst { it.contains("name") || it.contains("naam") }
    val ageIdx = header.indexOfFirst { it.contains("age") || it.contains("umar") || it.contains("vay") }
    val hasHeader = nameIdx >= 0 || ageIdx >= 0

    val dataLines = if (hasHeader) lines.drop(1) else lines
    return dataLines.mapNotNull { line ->
        val cols = split(line)
        if (cols.isEmpty()) return@mapNotNull null
        val name: String
        val age: String
        if (hasHeader && nameIdx >= 0) {
            name = cols.getOrNull(nameIdx) ?: ""
            age = if (ageIdx >= 0) cols.getOrNull(ageIdx)?.filter { it.isDigit() } ?: "" else ""
        } else {
            val e = parseCell(line)
            name = e.name; age = e.age
        }
        if (name.isBlank()) null else Entry(name, age)
    }
}
