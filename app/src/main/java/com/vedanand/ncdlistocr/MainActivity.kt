package com.vedanand.ncdlistocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Xml
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    var tableScreen by remember { mutableStateOf(false) }

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
                showGrid = true
                status = "$ OCR DONE: " + list.size + " CELLS. Niche table ma cell tap karo"
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

    // --- Export the reviewed list as an Excel workbook ---
    val xlsxSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            try {
                writeEntriesAsXlsx(context, uri, entries)
                status = "$ EXCEL EXPORTED: " + entries.size + " ROWS"
            } catch (e: Exception) {
                status = "$ EXCEL EXPORT FAILED: " + (e.message ?: "")
            }
        }
    }

    // --- Excel / CSV file import ---
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val name = (uri.lastPathSegment ?: "").lowercase(Locale.ROOT)
                val isXlsx = name.endsWith(".xlsx") || name.endsWith(".xls")
                if (isXlsx) {
                    val grid = parseXlsx(context, uri)
                    entries = gridToEntries(grid)
                    status = "$ EXCEL IMPORTED: " + entries.size + " ROWS"
                } else {
                    val text = context.contentResolver.openInputStream(uri)!!
                        .bufferedReader().use { it.readText() }
                    entries = parseCsv(text)
                    status = "$ CSV IMPORTED: " + entries.size + " ROWS"
                }
            } catch (e: Exception) {
                status = "$ FILE READ FAILED: " + (e.message ?: "")
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
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                // Header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "> NCD_LIST_OCR v2.6_",
                        color = TERM_TEAL, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                    Text(
                        "developed by Vin\u2206y Makwana",
                        color = TERM_TEAL.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace, fontSize = 9.sp
                    )
                }
                Text(
                    status,
                    color = TERM_TEAL.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
                Spacer(Modifier.height(6.dp))

                // Source + tools
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TermButton("CAMERA", Modifier.weight(1f), fontSize = 10.sp) { launchCamera() }
                    TermButton("GALLERY", Modifier.weight(1f), fontSize = 10.sp) {
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    TermButton("XLSX/CSV", Modifier.weight(1f), fontSize = 10.sp) {
                        filePicker.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "text/csv", "text/plain", "text/comma-separated-values", "application/csv"
                        ))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TermButton("ROTATE", Modifier.weight(1f), fontSize = 10.sp, enabled = bitmap != null && !loading) { rotate90() }
                    TermButton(if (cropMode) "CROPPING..." else "CROP", Modifier.weight(1f), fontSize = 10.sp, enabled = bitmap != null && !loading) {
                        cropMode = !cropMode; cropStart = null; cropEnd = null
                        status = if (cropMode) "$ CROP: photo par drag karo" else "$ CROP CANCELLED"
                    }
                    TermButton("AUTOCROP", Modifier.weight(1f), fontSize = 10.sp, enabled = bitmap != null && !loading) { autoCrop() }
                }
                if (showGrid && cells.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TermButton("TABLE VIEW", Modifier.weight(1f), fontSize = 10.sp) {
                            tableScreen = true
                        }
                        TermButton("EXPORT XLSX", Modifier.weight(1f), fontSize = 10.sp, enabled = entries.isNotEmpty()) {
                            xlsxSaver.launch("NCD-List-${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.xlsx")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // ---- 1) PHOTO (top, fixed height, selection overlay active) ----
                val bmp = bitmap
                if (bmp != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .background(TERM_PANEL, RoundedCornerShape(8.dp))
                            .border(1.dp, TERM_DIM, RoundedCornerShape(8.dp))
                    ) {
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
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TermButton("APPLY CROP", fontSize = 10.sp) {
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
                                TermButton("CANCEL", fontSize = 10.sp, color = TERM_WARN) {
                                    cropMode = false; cropStart = null; cropEnd = null
                                }
                            }
                        } else {
                            Canvas(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(cells, scale, offX, offY) {
                                        detectTapGestures(
                                            onTap = { tap ->
                                                val c = hitCell(tap, cells, scale, offX, offY) ?: return@detectTapGestures
                                                val key = c.blockIndex to c.lineIndex
                                                selected = if (key in selected) selected - key else selected + key
                                                status = "$ SELECTED " + selected.size + " CELLS"
                                            },
                                            onLongPress = { tap ->
                                                val tapped = hitCell(tap, cells, scale, offX, offY) ?: return@detectTapGestures
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
                    Spacer(Modifier.height(6.dp))
                }

                // ---- 2) EXCEL TABLE (middle, selectable cells) ----
                if (showGrid && cells.isNotEmpty()) {
                    val colGroups = clusterColumns(cells)
                    val colIndexOf = mutableMapOf<Pair<Int, Int>, Int>()
                    colGroups.forEachIndexed { ci, grp ->
                        grp.forEach { colIndexOf[it.blockIndex to it.lineIndex] = ci }
                    }
                    val rows = gridRows(cells)
                    val nCols = colGroups.size
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1.2f)
                            .background(TERM_PANEL, RoundedCornerShape(8.dp))
                            .border(1.dp, TERM_DIM, RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                "",
                                modifier = Modifier
                                    .width(28.dp)
                                    .background(TERM_DIM)
                                    .border(0.5.dp, TERM_DIM)
                                    .padding(3.dp)
                            )
                            repeat(nCols) { ci ->
                                Text(
                                    columnLetter(ci),
                                    color = TERM_TEAL,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .width(86.dp)
                                        .background(TERM_DIM)
                                        .border(0.5.dp, TERM_DIM)
                                        .padding(3.dp)
                                )
                            }
                        }
                        rows.forEachIndexed { ri, row ->
                            val byCol = mutableMapOf<Int, OcrCell>()
                            row.forEach { colIndexOf[it.blockIndex to it.lineIndex]?.let { ci -> byCol[ci] = it } }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                Text(
                                    (ri + 1).toString(),
                                    color = TERM_DIM,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .width(28.dp)
                                        .background(TERM_PANEL)
                                        .border(0.5.dp, TERM_DIM)
                                        .padding(3.dp)
                                )
                                repeat(nCols) { ci ->
                                    val cell = byCol[ci]
                                    val key = cell?.let { it.blockIndex to it.lineIndex }
                                    val isSel = key != null && key in selected
                                    Text(
                                        cell?.text ?: "",
                                        color = if (isSel) Color.Black else TERM_TEXT,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .width(86.dp)
                                            .background(
                                                when {
                                                    isSel -> TERM_TEAL
                                                    cell != null -> TERM_BG
                                                    else -> TERM_PANEL
                                                }
                                            )
                                            .border(0.5.dp, if (isSel) TERM_TEAL else TERM_DIM)
                                            .clickable(enabled = cell != null) {
                                                if (key != null) {
                                                    selected = if (isSel) selected - key else selected + key
                                                }
                                            }
                                            .padding(horizontal = 5.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // ---- 3) NAME + AGE LIST (bottom) ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TermButton("PAIR & ADD (" + selected.size + ")", Modifier.weight(1f), fontSize = 10.sp, enabled = selected.isNotEmpty()) {
                        val sel = cells.filter { (it.blockIndex to it.lineIndex) in selected }
                        val pairs = pairSelected(sel)
                        entries = entries + pairs
                        selected = emptySet()
                        status = "$ ADDED " + pairs.size + " ENTRIES"
                    }
                    TermButton("AUTO PARSE", Modifier.weight(1f), fontSize = 10.sp, enabled = cells.isNotEmpty() && !loading) {
                        entries = cells.map { parseCell(it.text) }.filter { it.name.isNotBlank() }
                        status = "$ PARSED " + entries.size + " ENTRIES"
                    }
                }
                Spacer(Modifier.height(4.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(TERM_DIM, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("NAME", color = TERM_TEAL, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("AGE", color = TERM_TEAL, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (entries.isEmpty()) {
                        Text(
                            "$ list khali - cell select kari ne PAIR & ADD dabao",
                            color = TERM_TEAL.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(entries.size) { i ->
                                val e = entries[i]
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 1.dp)
                                        .background(TERM_PANEL, RoundedCornerShape(3.dp))
                                        .clickable { editingIndex = i }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "> " + e.name,
                                        color = TERM_TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp
                                    )
                                    Text(
                                        e.age.ifBlank { "_" },
                                        color = TERM_BLUE, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TermButton("COPY (" + entries.size + ")", Modifier.weight(1f), fontSize = 10.sp, enabled = entries.isNotEmpty()) {
                        val text = entries.joinToString("\n") { (it.name + " " + it.age).trim() }
                        clipboard.setText(AnnotatedString(text))
                        status = "$ COPIED " + entries.size + " LINES TO CLIPBOARD"
                    }
                    TermButton("CLEAR", Modifier.weight(1f), fontSize = 10.sp, enabled = entries.isNotEmpty()) {
                        entries = emptyList(); status = "$ LIST CLEARED"
                    }
                }
            }

            if (tableScreen) {
                Dialog(
                    onDismissRequest = { tableScreen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(Modifier.fillMaxSize(), color = TERM_BG) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("EXCEL TABLE", color = TERM_TEAL, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                TermButton("BACK", fontSize = 10.sp) { tableScreen = false }
                            }
                            Text("Tap any value to copy that cell", color = TERM_TEAL.copy(alpha = 0.65f), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth().background(TERM_DIM).padding(8.dp)) {
                                Text("NAME", Modifier.weight(1f), color = TERM_TEAL, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text("AGE", Modifier.width(72.dp), color = TERM_TEAL, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            if (entries.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("First select OCR cells and use PAIR & ADD or AUTO PARSE.", color = TERM_TEXT, fontFamily = FontFamily.Monospace)
                                }
                            } else {
                                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                    items(entries.size) { i ->
                                        val row = entries[i]
                                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(TERM_PANEL).padding(8.dp)) {
                                            Text(row.name, Modifier.weight(1f).clickable {
                                                clipboard.setText(AnnotatedString(row.name)); status = "$ COPIED NAME CELL"
                                            }, color = TERM_TEXT, fontFamily = FontFamily.Monospace)
                                            Text(row.age, Modifier.width(72.dp).clickable {
                                                clipboard.setText(AnnotatedString(row.age)); status = "$ COPIED AGE CELL"
                                            }, color = TERM_BLUE, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TermButton("COPY ALL", Modifier.weight(1f), fontSize = 10.sp) {
                                        val text = entries.joinToString("\n") { "${it.name}\t${it.age}" }
                                        clipboard.setText(AnnotatedString(text)); status = "$ COPIED TABLE"
                                    }
                                    TermButton("EXPORT XLSX", Modifier.weight(1f), fontSize = 10.sp) {
                                        xlsxSaver.launch("NCD-List-${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.xlsx")
                                    }
                                }
                            }
                        }
                    }
                }
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
                            TermButton("SAVE", fontSize = 10.sp) {
                                entries = entries.toMutableList().also { it[idx] = Entry(name.trim(), age) }
                                editingIndex = null
                            }
                        },
                        dismissButton = {
                            Row {
                                TermButton("DELETE", fontSize = 10.sp, color = TERM_WARN) {
                                    entries = entries.toMutableList().also { it.removeAt(idx) }
                                    editingIndex = null
                                }
                                Spacer(Modifier.width(8.dp))
                                TermButton("CANCEL", fontSize = 10.sp) { editingIndex = null }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TermButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = TERM_TEAL, fontSize: androidx.compose.ui.unit.TextUnit = 12.sp, onClick: () -> Unit) {
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
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Write a minimal standards-compliant XLSX workbook with Name and Age columns. */
fun writeEntriesAsXlsx(context: Context, uri: Uri, entries: List<Entry>) {
    val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
    ZipOutputStream(output).use { zip ->
        fun file(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        file("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        file("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        file("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="NCD List" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        file("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        val rows = buildString {
            append("""<row r="1"><c r="A1" t="inlineStr"><is><t>Name</t></is></c><c r="B1" t="inlineStr"><is><t>Age</t></is></c></row>""")
            entries.forEachIndexed { index, entry ->
                val r = index + 2
                append("""<row r="$r"><c r="A$r" t="inlineStr"><is><t xml:space="preserve">${xml(entry.name)}</t></is></c><c r="B$r" t="inlineStr"><is><t>${xml(entry.age)}</t></is></c></row>""")
            }
        }
        file("xl/worksheets/sheet1.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""")
    }
}

// ---------- Image helpers ----------

/** Load bitmap with EXIF rotation applied (fixes auto-rotate problem). */
fun loadBitmapWithExif(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
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

private fun hitCell(tap: Offset, cells: List<OcrCell>, scale: Float, offX: Float, offY: Float): OcrCell? {
    for (cell in cells) {
        val r = Rect(
            (cell.rect.left * scale + offX).toInt(),
            (cell.rect.top * scale + offY).toInt(),
            (cell.rect.right * scale + offX).toInt(),
            (cell.rect.bottom * scale + offY).toInt()
        )
        if (r.contains(tap.x.toInt(), tap.y.toInt())) return cell
    }
    return null
}

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

/** Cluster OCR cells into visual columns (sorted left to right). */
fun clusterColumns(cells: List<OcrCell>): List<List<OcrCell>> {
    val cols = mutableListOf<MutableList<OcrCell>>()
    cells.sortedBy { it.rect.left }.forEach { c ->
        val col = cols.lastOrNull { c0 ->
            val anchor = c0[0]
            val overlap = minOf(c.rect.right, anchor.rect.right) -
                    maxOf(c.rect.left, anchor.rect.left)
            overlap > 0.3f * minOf(c.rect.width().toFloat(), anchor.rect.width().toFloat())
        }
        if (col != null) col.add(c) else cols.add(mutableListOf(c))
    }
    return cols
}

fun columnLetter(index: Int): String {
    var i = index
    val sb = StringBuilder()
    while (i >= 0) {
        sb.insert(0, ('A' + (i % 26)))
        i = i / 26 - 1
    }
    return sb.toString()
}

/**
 * Excel-style: from selected cells, detect name column and age column,
 * then pair row-wise. If only one column selected, parse each cell directly.
 */
fun pairSelected(sel: List<OcrCell>): List<Entry> {
    if (sel.isEmpty()) return emptyList()
    val cols = clusterColumns(sel)
    if (cols.size == 1) {
        return cols[0].sortedBy { it.rect.top }
            .map { parseCell(it.text) }
            .filter { it.name.isNotBlank() }
    }
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
 * Grid (from xlsx or csv) -> entries. Header-aware.
 */
fun gridToEntries(grid: List<List<String>>): List<Entry> {
    if (grid.isEmpty()) return emptyList()
    val header = grid[0].map { it.trim().lowercase(Locale.ROOT) }
    val nameIdx = header.indexOfFirst { it.contains("name") || it.contains("naam") }
    val ageIdx = header.indexOfFirst { it.contains("age") || it.contains("umar") || it.contains("vay") }
    val hasHeader = nameIdx >= 0 || ageIdx >= 0
    val data = if (hasHeader) grid.drop(1) else grid
    return data.mapNotNull { row ->
        if (row.isEmpty()) return@mapNotNull null
        val name: String
        val age: String
        if (hasHeader && nameIdx >= 0) {
            name = row.getOrNull(nameIdx)?.trim() ?: ""
            age = if (ageIdx >= 0) row.getOrNull(ageIdx)?.filter { it.isDigit() } ?: "" else ""
        } else {
            name = row.firstOrNull { it.trim().isNotEmpty() && it.trim().toIntOrNull() == null }?.trim() ?: ""
            age = row.firstOrNull { (it.trim().toIntOrNull() ?: 0) in 18..120 }?.trim() ?: ""
        }
        if (name.isBlank()) null else Entry(name, age)
    }
}

/**
 * CSV import.
 */
fun parseCsv(raw: String): List<Entry> {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    val grid = lines.map { line ->
        line.split(Regex(",|\t|;")).map { it.trim().removeSurrounding("\"") }
    }
    return gridToEntries(grid)
}

/**
 * Minimal .xlsx reader (no external library).
 */
fun parseXlsx(context: Context, uri: Uri): List<List<String>> {
    val shared = mutableListOf<String>()
    val sheets = mutableListOf<List<List<String>>>()
    context.contentResolver.openInputStream(uri)?.use { fis ->
        ZipInputStream(fis).use { zis ->
            var ze = zis.nextEntry
            while (ze != null) {
                val zn = ze.name
                if (zn == "xl/sharedStrings.xml") {
                    val parser = Xml.newPullParser()
                    parser.setInput(zis, null)
                    var inSi = false
                    val sb = StringBuilder()
                    var ev = parser.eventType
                    while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                                "si" -> { inSi = true; sb.clear() }
                                "t" -> if (inSi) sb.append(parser.nextText())
                            }
                            org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "si") {
                                shared.add(sb.toString()); inSi = false
                            }
                        }
                        ev = parser.next()
                    }
                } else if (zn.startsWith("xl/worksheets/sheet") && zn.endsWith(".xml") && sheets.isEmpty()) {
                    val parser = Xml.newPullParser()
                    parser.setInput(zis, null)
                    val rows = mutableListOf<List<String>>()
                    var curRow: MutableList<String>? = null
                    var ev = parser.eventType
                    while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                                "row" -> { curRow = mutableListOf() }
                                "c" -> {
                                    val ref = parser.getAttributeValue(null, "r") ?: ""
                                    val t = parser.getAttributeValue(null, "t")
                                    val col = colIndexFromRef(ref)
                                    val cellText: String = when (t) {
                                        "s" -> {
                                            val v = readV(parser)
                                            v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                        }
                                        "inlineStr" -> readT(parser)
                                        else -> readV(parser)
                                    }
                                    while (curRow != null && curRow.size < col) curRow.add("")
                                    curRow?.add(cellText)
                                }
                            }
                            org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "row") {
                                curRow?.let { rows.add(it) }; curRow = null
                            }
                        }
                        ev = parser.next()
                    }
                    sheets.add(rows)
                }
                ze = zis.nextEntry
            }
        }
    }
    return sheets.firstOrNull() ?: emptyList()
}

private fun colIndexFromRef(ref: String): Int {
    var col = 0
    for (ch in ref) {
        if (ch.isLetter()) col = col * 26 + (ch.uppercaseChar() - 'A' + 1) else break
    }
    return (col - 1).coerceAtLeast(0)
}

private fun readV(parser: org.xmlpull.v1.XmlPullParser): String {
    var ev = parser.next()
    val sb = StringBuilder()
    while (ev != org.xmlpull.v1.XmlPullParser.END_TAG || parser.name != "c") {
        if (ev == org.xmlpull.v1.XmlPullParser.TEXT) sb.append(parser.text)
        if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "v") return sb.toString()
        ev = parser.next()
    }
    return sb.toString()
}

private fun readT(parser: org.xmlpull.v1.XmlPullParser): String {
    var ev = parser.next()
    val sb = StringBuilder()
    while (!(ev == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "c")) {
        if (ev == org.xmlpull.v1.XmlPullParser.TEXT) sb.append(parser.text)
        ev = parser.next()
    }
    return sb.toString().trim()
}
