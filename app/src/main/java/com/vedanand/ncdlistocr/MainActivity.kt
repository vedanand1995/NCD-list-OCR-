package com.vedanand.ncdlistocr

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

data class Person(
    val name: String,
    val surname: String,
    val phone: String,
    val age: String,
    val gender: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var people by remember { mutableStateOf<List<Person>>(emptyList()) }
    var status by remember { mutableStateOf("Photo select karo - list auto banse") }
    var loading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            loading = true
            status = "OCR chalu chhe..."
            runOcr(applicationContext, uri) { result ->
                loading = false
                people = result
                status = if (result.isEmpty())
                    "Koi list mali nathi - photo spashT hovi joiye."
                else
                    "${result.size} entries malya - photo padi ane list bani."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("NCD List OCR") })
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Gallery thi Photo Padi")
            }
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 13.sp)
            if (loading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))

            if (people.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${people.size} people", fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        val csv = buildString {
                            appendLine("Name,Surname,Phone,Age,Gender")
                            people.forEach {
                                appendLine("${it.name},${it.surname},${it.phone},${it.age},${it.gender}")
                            }
                        }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(android.content.Intent.EXTRA_TEXT, csv)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Export CSV"))
                    }) { Text("Export CSV") }
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(people) { p ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                if (p.surname.isNotBlank()) Text(p.surname, fontSize = 13.sp)
                                if (p.phone.isNotBlank()) Text(p.phone, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${p.age} yrs", fontWeight = FontWeight.Bold)
                                Text(p.gender, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun runOcr(context: android.content.Context, uri: Uri, onDone: (List<Person>) -> Unit) {
    val resolver = context.contentResolver
    val bitmap = BitmapFactory.decodeStream(resolver.openInputStream(uri))
    if (bitmap == null) {
        onDone(emptyList())
        return
    }
    val image = InputImage.fromBitmap(bitmap, 0)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(image)
        .addOnSuccessListener { visionText ->
            onDone(parseLines(visionText.text))
        }
        .addOnFailureListener { onDone(emptyList()) }
}

/**
 * Parse OCR text lines like:
 *   ICDS-12 NICHALI COLLONI TADWADI | jaydevi | | | 8127210887 | 33 | F
 *   dinesh hareejan 37
 */
fun parseLines(raw: String): List<Person> {
    val people = mutableListOf<Person>()
    val noise = Regex("(?i)icds|nicha?li|collo?ni|tadwadi|\\d+-\\d+")

    raw.lines().forEach { line0 ->
        val line = line0.replace("|", " ").replace(Regex("[\\u2013\\u2014]"), " ").trim()
        if (line.length < 4) return@forEach

        // phone: 10-digit starting 6-9
        val phoneMatch = Regex("\\b[6-9]\\d{9}\\b").find(line)
        val phone = phoneMatch?.value ?: ""

        // gender: standalone M or F
        val genderMatch = Regex("\\b([MFmf])\\b").findAll(line).lastOrNull()
        val gender = genderMatch?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""

        // age: 2-digit number 18-99 (not the phone)
        val ageMatch = Regex("\\b(\\d{2})\\b").findAll(line)
            .map { it.groupValues[1].toInt() }
            .firstOrNull { it in 18..99 }

        var text = line
            .replace(phoneMatch?.value ?: "", " ")
            .let { if (genderMatch != null) it.replaceRange(genderMatch.range, "  ") else it }
        if (ageMatch != null) text = text.replace(Regex("\\b${ageMatch}\\b"), " ")

        text = noise.replace(text, " ")
            .replace(Regex("[^A-Za-z\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isBlank() && phone.isBlank()) return@forEach

        val words = text.split(" ").filter { it.length > 1 }
        val name = words.firstOrNull() ?: ""
        val surname = words.drop(1).joinToString(" ")

        if (name.isNotBlank() || phone.isNotBlank()) {
            people.add(
                Person(
                    name = name.replaceFirstChar { it.uppercase(Locale.ROOT) },
                    surname = surname.replaceFirstChar { it.uppercase(Locale.ROOT) },
                    phone = phone,
                    age = ageMatch?.toString() ?: "",
                    gender = gender
                )
            )
        }
    }
    return people
}
