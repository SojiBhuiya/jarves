package com.sojibhuiya.jarves

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

private val Context.paiDataStore by preferencesDataStore(name = "assistant_settings")
private val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
private val GEMINI_ENABLED = booleanPreferencesKey("gemini_enabled")
private val GEMINI_KEY_ENCRYPTED = stringPreferencesKey("gemini_key_encrypted")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AssistantApp() }
    }
}

@Composable
private fun AssistantApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by context.paiDataStore.data.collectAsState(initial = null)
    var screen by remember { mutableStateOf("home") }
    var assistantNameInput by remember(prefs) { mutableStateOf(prefs?.get(ASSISTANT_NAME).orEmpty()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var assistantReply by remember { mutableStateOf("") }
    var userText by remember { mutableStateOf("") }
    var recognizedText by remember { mutableStateOf("") }
    var speechStatus by remember { mutableStateOf("মাইক্রোফোনে কথা বলতে মাঝের বাটনে চাপ দিন") }
    var isListening by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }

    val assistantName = prefs?.get(ASSISTANT_NAME).orEmpty()
    val geminiEnabled = prefs?.get(GEMINI_ENABLED) ?: false
    val encryptedKey = prefs?.get(GEMINI_KEY_ENCRYPTED).orEmpty()
    val hasKey = encryptedKey.isNotBlank()

    val tts = remember(context) {
        TextToSpeech(context) { result -> ttsReady = result == TextToSpeech.SUCCESS }
    }
    DisposableEffect(tts) {
        onDispose { tts.stop(); tts.shutdown() }
    }

    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true; speechStatus = "শুনছি… কথা বলুন" }
            override fun onBeginningOfSpeech() { speechStatus = "আপনার কথা শুনছি…" }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false; speechStatus = "উত্তর তৈরি করছি…" }
            override fun onError(error: Int) {
                isListening = false
                speechStatus = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোনের অনুমতি নেই"
                    SpeechRecognizer.ERROR_NO_MATCH -> "কথা বুঝতে পারিনি, আবার বলুন"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service-এর নেটওয়ার্ক সমস্যা"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কোনো কথা শোনা যায়নি"
                    else -> "ভয়েস ইনপুটে সমস্যা হয়েছে ($error)"
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                recognizedText = spoken
                if (spoken.isNotBlank()) {
                    if (geminiEnabled && hasKey) {
                        scope.launch {
                            busy = true
                            speechStatus = "Gemini ভাবছে…"
                            val answer = withContext(Dispatchers.IO) {
                                runCatching { GeminiApi.generate(SecretVault.decrypt(context, encryptedKey), spoken, assistantName) }
                                    .getOrElse { "সমস্যা হয়েছে: ${it.message ?: "Gemini request failed"}" }
                            }
                            assistantReply = answer
                            busy = false
                            speechStatus = "উত্তর প্রস্তুত"
                            if (ttsReady) {
                                tts.language = Locale("bn", "BD")
                                tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "assistant_answer")
                            }
                        }
                    } else {
                        speechStatus = if (!geminiEnabled) "Gemini বন্ধ আছে। Settings থেকে চালু করুন" else "Gemini API Key যোগ করুন"
                    }
                } else speechStatus = "কথা বুঝতে পারিনি, আবার চেষ্টা করুন"
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { recognizedText = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer?.destroy() }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening(recognizer) { speechStatus = it }
        else speechStatus = "ভয়েস ব্যবহারের জন্য Microphone permission দিন"
    }

    val background = Brush.verticalGradient(listOf(Color(0xFF071321), Color(0xFF02060D)))
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF55D9FF), background = Color(0xFF02060D))) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF02060D)) {
            Box(Modifier.fillMaxSize().background(background)) {
                when {
                    prefs == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    assistantName.isBlank() -> NameSetup(
                        value = assistantNameInput,
                        onChange = { assistantNameInput = it },
                        onSave = {
                            scope.launch { context.paiDataStore.edit { it[ASSISTANT_NAME] = assistantNameInput.trim() } }
                        }
                    )
                    screen == "settings" -> SettingsScreen(
                        assistantName = assistantNameInput.ifBlank { assistantName },
                        onNameChange = { assistantNameInput = it },
                        apiKey = apiKeyInput,
                        onApiKeyChange = { apiKeyInput = it },
                        geminiEnabled = geminiEnabled,
                        hasKey = hasKey,
                        status = status,
                        busy = busy,
                        onBack = { screen = "home"; status = "" },
                        onSaveName = {
                            scope.launch {
                                context.paiDataStore.edit { it[ASSISTANT_NAME] = assistantNameInput.trim() }
                                status = "Assistant-এর নাম সংরক্ষণ হয়েছে"
                            }
                        },
                        onSaveKey = {
                            scope.launch {
                                runCatching {
                                    val encrypted = SecretVault.encrypt(context, apiKeyInput.trim())
                                    context.paiDataStore.edit { it[GEMINI_KEY_ENCRYPTED] = encrypted }
                                    apiKeyInput = ""
                                    status = "Gemini API Key নিরাপদে সংরক্ষণ হয়েছে"
                                }.onFailure { status = "Key সংরক্ষণ করা যায়নি: ${it.message}" }
                            }
                        },
                        onToggle = { enabled ->
                            scope.launch {
                                context.paiDataStore.edit { it[GEMINI_ENABLED] = enabled }
                                status = if (enabled) "Gemini চালু হয়েছে" else "Gemini বন্ধ হয়েছে"
                            }
                        },
                        onTest = {
                            scope.launch {
                                busy = true
                                status = "Gemini connection পরীক্ষা হচ্ছে…"
                                val key = runCatching { SecretVault.decrypt(context, encryptedKey) }.getOrElse { "" }
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { GeminiApi.generate(key, "Reply with only: Connection successful.", assistantName) }
                                }
                                status = if (result.isSuccess) "Connection successful! Gemini API কাজ করছে।"
                                    else "Connection failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                                busy = false
                            }
                        }
                    )
                    else -> HomeScreen(
                        assistantName = assistantName,
                        geminiEnabled = geminiEnabled,
                        hasKey = hasKey,
                        recognizedText = recognizedText,
                        reply = assistantReply,
                        speechStatus = speechStatus,
                        isListening = isListening,
                        busy = busy,
                        userText = userText,
                        onUserTextChange = { userText = it },
                        onOpenSettings = { screen = "settings"; assistantNameInput = assistantName },
                        onListen = {
                            if (recognizer == null) speechStatus = "এই ফোনে Speech Recognition service নেই"
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startListening(recognizer) { speechStatus = it; isListening = true }
                            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSend = {
                            val prompt = userText.trim()
                            if (prompt.isNotBlank()) {
                                if (!geminiEnabled) speechStatus = "Gemini বন্ধ আছে। Settings থেকে চালু করুন"
                                else if (!hasKey) speechStatus = "Settings থেকে Gemini API Key যোগ করুন"
                                else scope.launch {
                                    busy = true
                                    assistantReply = ""
                                    speechStatus = "Gemini উত্তর তৈরি করছে…"
                                    val answer = withContext(Dispatchers.IO) {
                                        runCatching { GeminiApi.generate(SecretVault.decrypt(context, encryptedKey), prompt, assistantName) }
                                    }
                                    assistantReply = answer.getOrElse { "সমস্যা হয়েছে: ${it.message ?: "Request failed"}" }
                                    busy = false
                                    speechStatus = "উত্তর প্রস্তুত"
                                    userText = ""
                                    if (ttsReady) {
                                        tts.language = Locale("bn", "BD")
                                        tts.speak(assistantReply, TextToSpeech.QUEUE_FLUSH, null, "assistant_answer")
                                    }
                                }
                            }
                        },
                        onSpeakReply = {
                            if (assistantReply.isNotBlank() && ttsReady) {
                                val lang = tts.setLanguage(Locale("bn", "BD"))
                                if (lang < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
                                tts.speak(assistantReply, TextToSpeech.QUEUE_FLUSH, null, "assistant_answer")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NameSetup(value: String, onChange: (String) -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Give your assistant a name", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("এই নামটি অ্যাপের ভেতরে Assistant-এর পরিচয় হবে।", color = Color(0xFFB8C9D8))
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text("Assistant name") }, singleLine = true)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSave, enabled = value.trim().isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun HomeScreen(
    assistantName: String, geminiEnabled: Boolean, hasKey: Boolean,
    recognizedText: String, reply: String, speechStatus: String, isListening: Boolean, busy: Boolean,
    userText: String, onUserTextChange: (String) -> Unit, onOpenSettings: () -> Unit,
    onListen: () -> Unit, onSend: () -> Unit, onSpeakReply: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenSettings) { Text("⚙ Settings", color = Color(0xFF8DDBF4)) }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.size(154.dp).background(Color(0xFF0D2940), CircleShape)
                .clickable(enabled = !isListening && !busy, onClick = onListen),
            contentAlignment = Alignment.Center
        ) {
            Text(if (isListening) "●" else "◉", color = Color(0xFF55D9FF), fontSize = 66.sp)
        }
        Spacer(Modifier.height(22.dp))
        Text("হ্যালো! আমি $assistantName।", color = Color(0xFFEAF7FF), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Text("কীভাবে আপনাকে সাহায্য করতে পারি?", color = Color(0xFFB8C9D8), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            when { !geminiEnabled -> "Gemini: OFF"; !hasKey -> "Gemini API Key প্রয়োজন"; else -> "Gemini: ON" },
            color = if (geminiEnabled && hasKey) Color(0xFF66D9A8) else Color(0xFFFFC36B),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onListen, enabled = !isListening && !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text(if (isListening) "Listening…" else "🎙 Speak to Assistant")
        }
        Spacer(Modifier.height(10.dp))
        Text(speechStatus, color = Color(0xFF9FB3C5), fontSize = 13.sp)
        if (recognizedText.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            MessageCard("আপনি বলেছেন", recognizedText)
        }
        if (reply.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            MessageCard("$assistantName", reply)
            TextButton(onClick = onSpeakReply) { Text("🔊 Speak answer") }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = userText, onValueChange = onUserTextChange, modifier = Modifier.fillMaxWidth(),
            label = { Text("Type a message") }, placeholder = { Text("বাংলা / English / Banglish") }
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSend, enabled = !busy && userText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send to $assistantName")
        }
    }
}

@Composable
private fun MessageCard(title: String, message: String) {
    Surface(Modifier.fillMaxWidth(), color = Color(0xFF10283A), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color(0xFF55D9FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(message, color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    assistantName: String, onNameChange: (String) -> Unit, apiKey: String, onApiKeyChange: (String) -> Unit,
    geminiEnabled: Boolean, hasKey: Boolean, status: String, busy: Boolean,
    onBack: () -> Unit, onSaveName: () -> Unit, onSaveKey: () -> Unit,
    onToggle: (Boolean) -> Unit, onTest: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        TextButton(onClick = onBack) { Text("← Back", color = Color(0xFF8DDBF4)) }
        Text("Settings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text("Assistant identity", color = Color(0xFF55D9FF), fontWeight = FontWeight.SemiBold)
        OutlinedTextField(assistantName, onNameChange, modifier = Modifier.fillMaxWidth(), label = { Text("Assistant name") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSaveName, enabled = assistantName.trim().isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save assistant name") }
        Spacer(Modifier.height(24.dp))
        Divider(color = Color(0xFF29465A))
        Spacer(Modifier.height(20.dp))
        Text("AI Provider", color = Color(0xFF55D9FF), fontWeight = FontWeight.SemiBold)
        Text("Gemini API", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (geminiEnabled) "Enabled" else "Disabled", color = Color(0xFFB8C9D8))
                Text(if (hasKey) "API key saved securely" else "No API key saved", color = Color(0xFF8D9EAC), fontSize = 12.sp)
            }
            Switch(checked = geminiEnabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API Key") }, placeholder = { Text(if (hasKey) "Saved. Enter a new key to replace" else "Paste your API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSaveKey, enabled = apiKey.trim().isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Save API Key") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onTest, enabled = hasKey && !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Test Gemini Connection")
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(status, color = if (status.contains("successful") || status.contains("সংরক্ষণ") || status.contains("চালু")) Color(0xFF66D9A8) else Color(0xFFFFC36B), fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("API key ফোনের Android Keystore দিয়ে encrypt করে সংরক্ষণ করা হয়। Key কখনো UI-তে আবার দেখানো হয় না।", color = Color(0xFF8D9EAC), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { /* link is provided in app instructions */ }) { Text("Create key: aistudio.google.com/apikey") }
    }
}

private fun startListening(recognizer: SpeechRecognizer?, onStatus: (String) -> Unit) {
    if (recognizer == null) { onStatus("Speech Recognition service পাওয়া যায়নি"); return }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "বাংলা বা English-এ কথা বলুন")
    }
    try { recognizer.startListening(intent); onStatus("মাইক্রোফোন চালু হচ্ছে…") }
    catch (_: Exception) { onStatus("ভয়েস ইনপুট শুরু করা যায়নি") }
}

private object GeminiApi {
    fun generate(apiKey: String, prompt: String, assistantName: String): String {
        require(apiKey.isNotBlank()) { "Gemini API Key পাওয়া যায়নি" }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            val systemPrompt = "You are $assistantName, a helpful personal AI assistant. Understand and respond naturally in the language the user uses, including Bengali, English, and Banglish. Be clear, friendly, accurate, and concise. Never claim to perform phone actions unless actually available."
            val body = JSONObject().put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", systemPrompt))))
                .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user").put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 1024))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            }
            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates") ?: throw IllegalStateException("Gemini কোনো উত্তর দেয়নি")
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini response format পাওয়া যায়নি")
            val result = buildString {
                for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()
            if (result.isBlank()) throw IllegalStateException("খালি উত্তর এসেছে")
            return result
        } finally { connection.disconnect() }
    }
}

private object SecretVault {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "agi_gemini_key_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", STORE)
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    fun encrypt(context: Context, plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(context: Context, encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12) { "সংরক্ষিত API key সঠিক নয়" }
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
