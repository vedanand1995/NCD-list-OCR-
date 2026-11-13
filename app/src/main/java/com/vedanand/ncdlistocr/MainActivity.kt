package com.vedanand.ncdlistocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

enum class SelectMode { NAME, AGE }

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
    var mode by remember { mutableStateOf(SelectMode.NAME) }
    var nameCol by remember { mutableStateOf<List<OcrCell>>(emptyList()) }
    var ageCol by remember { mutableStateOf<List<OcrCell>>(emptyList()) }
    var entries by remember { mutableStateOf(listOf<Entry>()) }
    var status by remember { mutableStateOf("$ SYSTEM READY") }
    var loading by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    fun resetSelection() { nameCol = emptyList(); ageCol = emptyList(); mode = SelectMode.NAME }

    // --- Gallery picker ---
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) processUri(context, uri) { b, c -> bitmap = b; cells = c; resetSelection() } }

    // --- Camera capture ---
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val u = photoUri
        if (ok && u != null) processUri(context, u) { b, c -> bitmap = b; cells = c; resetSelection() }
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
                    "> NCD_LIST_OCR v2.2_",
                    color = TERM_TEAL, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp
                )
                Text(
                    status,
                    color = TERM_TEAL.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton("CAMERA", Modifier.weight(1f)) { launchCamera() }
                    TermButton("GALLERY", Modifier.weight(1f)) {
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    TermButton("CSV", Modifier.weight(1f)) {
                        filePicker.launch(arrayOf("text/csv", "text/plain", "text/comma-separated-values", "application/csv"))
                    }
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

                        // Column-select overlay (Google Lens style)
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(cells, scale, offX, offY) {
                                    detectTapGestures { tap ->
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

                                        fun columnOf(anchor: OcrCell): List<OcrCell> =
                                            cells.filter { c ->
                                                val overlap = minOf(c.rect.right, anchor.rect.right) -
                                                        maxOf(c.rect.left, anchor.rect.left)
                                                overlap > 0.4f * minOf(
                                                    c.rect.width().toFloat(),
                                                    anchor.rect.width().toFloat()
                                                )
                                            }.sortedBy { it.rect.top }

                                        if (mode == SelectMode.NAME) {
                                            nameCol = columnOf(tapped)
                                            // AUTO: age column = nearest column to the RIGHT of name column
                                            val rightCells = cells.filter { c ->
                                                c !in nameCol && c.rect.left >= tapped.rect.right - tapped.rect.width() * 0.5f
                                            }
                                            val nearest = rightCells.minByOrNull { it.rect.left }
                                            ageCol = if (nearest != null) columnOf(nearest) else emptyList()
                                            mode = SelectMode.AGE
                                            status = if (ageCol.isEmpty())
                                                "$ NAME COL: " + nameCol.size + " rows. Age column mali nathi - manually tap karo"
                                            else
                                                "$ NAME " + nameCol.size + " + AGE " + ageCol.size + " auto-selected. PAIR & ADD dabao"
                                        } else {
                                            ageCol = columnOf(tapped)
                                            status = "$ AGE COLUMN: " + ageCol.size + " ROWS - PAIR & ADD dabao"
                                        }
                                    }
                                }
                        ) {
                            cells.forEach { cell ->
                                val rectF = androidx.compose.ui.geometry.Rect(
                                    cell.rect.left * scale + offX,
                                    cell.rect.top * scale + offY,
                                    cell.rect.right * scale + offX,
                                    cell.rect.bottom * scale + offY
                                )
                                when (cell) {
                                    in nameCol -> {
                                        drawRect(TERM_TEAL.copy(alpha = 0.30f), rectF)
                                        drawRect(TERM_TEAL, rectF, style = Stroke(width = 4f))
                                    }
                                    in ageCol -> {
                                        drawRect(TERM_BLUE.copy(alpha = 0.30f), rectF)
                                        drawRect(TERM_BLUE, rectF, style = Stroke(width = 4f))
                                    }
                                    else -> drawRect(TERM_BLUE.copy(alpha = 0.20f), rectF, style = Stroke(width = 2f))
                                }
                            }
                            if (loading) {
                                val y = scanY * size.height
                                drawLine(TERM_TEAL, Offset(0f, y), Offset(size.width, y), strokeWidth = 6f)
                                drawRect(
                                    TERM_TEAL.copy(alpha = 0.08f),
                                    androidx.compose.ui.geometry.Rect(0f, 0f, size.width, y)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton(
                        if (mode == SelectMode.NAME) "1:NAME COL <" else "1:NAME COL (" + nameCol.size + ")",
                        Modifier.weight(1f), color = TERM_TEAL, enabled = cells.isNotEmpty() && !loading
                    ) { mode = SelectMode.NAME; status = "$ TAP any cell in NAME column" }
                    TermButton(
                        if (mode == SelectMode.AGE) "2:AGE COL <" else "2:AGE COL (" + ageCol.size + ")",
                        Modifier.weight(1f), color = TERM_BLUE, enabled = cells.isNotEmpty() && !loading
                    ) { mode = SelectMode.AGE; status = "$ TAP any cell in AGE column" }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton("PAIR & ADD", Modifier.weight(1f), enabled = nameCol.isNotEmpty() && ageCol.isNotEmpty()) {
                        val pairs = pairColumns(nameCol, ageCol)
                        entries = entries + pairs
                        status = "$ ADDED " + pairs.size + " PAIRS"
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

// ---------- OCR ----------

private fun processUri(context: Context, uri: Uri, onDone: (Bitmap?, List<OcrCell>) -> Unit) {
    val bmp = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
    if (bmp == null) { onDone(null, emptyList()); return }
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
            onDone(bmp, list)
        }
        .addOnFailureListener { onDone(bmp, emptyList()) }
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
