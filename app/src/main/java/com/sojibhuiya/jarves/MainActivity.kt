package com.sojibhuiya.jarves

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

private val Context.agiDataStore by preferencesDataStore(name = "assistant_settings")
private val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
private val GEMINI_ENABLED = booleanPreferencesKey("gemini_enabled")
private val GEMINI_KEY_ENCRYPTED = stringPreferencesKey("gemini_key_encrypted")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AgiApp() }
    }
}

@Composable
private fun AgiApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by context.agiDataStore.data.collectAsState(initial = null)
    var screen by remember { mutableStateOf("home") }
    var assistantNameInput by remember(prefs) { mutableStateOf(prefs?.get(ASSISTANT_NAME).orEmpty()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("মাঝের বাটনে চাপ দিয়ে Live Voice চালু করুন") }
    var userTranscript by remember { mutableStateOf("") }
    var assistantTranscript by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf<GeminiLiveVoice?>(null) }

    val assistantName = prefs?.get(ASSISTANT_NAME).orEmpty()
    val geminiEnabled = prefs?.get(GEMINI_ENABLED) ?: false
    val encryptedKey = prefs?.get(GEMINI_KEY_ENCRYPTED).orEmpty()
    val hasKey = encryptedKey.isNotBlank()

    fun startLive() {
        if (!geminiEnabled) { status = "Settings থেকে Gemini চালু করুন"; return }
        if (!hasKey) { status = "Settings থেকে Gemini API Key যোগ করুন"; return }
        val key = runCatching { SecretVault.decrypt(encryptedKey) }.getOrElse {
            status = "সংরক্ষিত API Key পড়া যায়নি। নতুন Key দিন।"
            return
        }
        val live = GeminiLiveVoice(
            apiKey = key,
            assistantName = assistantName,
            onStatus = { value -> scope.launch { status = value; connected = value.contains("সংযুক্ত") || value.contains("শুনছি") || value.contains("কথা") } },
            onTranscript = { text, isAssistant ->
                scope.launch {
                    if (isAssistant) assistantTranscript = text else userTranscript = text
                }
            },
            onError = { message -> scope.launch { status = message; connected = false } }
        )
        engine = live
        live.start()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLive() else status = "Live Voice ব্যবহারের জন্য Microphone permission দিন"
    }

    DisposableEffect(Unit) {
        onDispose { engine?.stop() }
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
                            scope.launch { context.agiDataStore.edit { it[ASSISTANT_NAME] = assistantNameInput.trim() } }
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
                        onBack = { screen = "home" },
                        onSaveName = {
                            scope.launch {
                                context.agiDataStore.edit { it[ASSISTANT_NAME] = assistantNameInput.trim() }
                                status = "Assistant-এর নাম সংরক্ষণ হয়েছে"
                            }
                        },
                        onSaveKey = {
                            scope.launch {
                                runCatching {
                                    context.agiDataStore.edit { it[GEMINI_KEY_ENCRYPTED] = SecretVault.encrypt(apiKeyInput.trim()) }
                                    apiKeyInput = ""
                                    status = "Gemini API Key নিরাপদে সংরক্ষণ হয়েছে"
                                }.onFailure { status = "Key সংরক্ষণ করা যায়নি: ${it.message}" }
                            }
                        },
                        onToggle = { enabled ->
                            scope.launch {
                                if (!enabled) engine?.stop()
                                context.agiDataStore.edit { it[GEMINI_ENABLED] = enabled }
                                status = if (enabled) "Gemini Live চালু করা হয়েছে" else "Gemini বন্ধ হয়েছে"
                            }
                        }
                    )
                    else -> HomeScreen(
                        assistantName = assistantName,
                        geminiEnabled = geminiEnabled,
                        hasKey = hasKey,
                        connected = connected,
                        status = status,
                        userTranscript = userTranscript,
                        assistantTranscript = assistantTranscript,
                        onOpenSettings = { screen = "settings"; assistantNameInput = assistantName },
                        onVoiceClick = {
                            if (connected) {
                                engine?.stop()
                                engine = null
                                connected = false
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startLive()
                            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
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
    assistantName: String,
    geminiEnabled: Boolean,
    hasKey: Boolean,
    connected: Boolean,
    status: String,
    userTranscript: String,
    assistantTranscript: String,
    onOpenSettings: () -> Unit,
    onVoiceClick: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenSettings) { Text("⚙ Settings", color = Color(0xFF8DDBF4)) }
        }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.size(166.dp)
                .background(if (connected) Color(0xFF12465A) else Color(0xFF0D2940), CircleShape)
                .clickable(onClick = onVoiceClick),
            contentAlignment = Alignment.Center
        ) {
            Text(if (connected) "◉" else "◎", color = Color(0xFF55D9FF), fontSize = 76.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text("হ্যালো! আমি $assistantName।", color = Color(0xFFEAF7FF), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Text("কীভাবে আপনাকে সাহায্য করতে পারি?", color = Color(0xFFB8C9D8), fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            when { !geminiEnabled -> "Gemini: OFF"; !hasKey -> "Gemini API Key প্রয়োজন"; connected -> "LIVE VOICE CONNECTED"; else -> "Gemini Live প্রস্তুত" },
            color = if (connected) Color(0xFF66D9A8) else Color(0xFFFFC36B),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onVoiceClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (connected) "■ Stop Live Voice" else "🎙 Start Live Conversation")
        }
        Spacer(Modifier.height(12.dp))
        Text(status, color = Color(0xFF9FB3C5), fontSize = 13.sp)
        if (userTranscript.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            MessageCard("আপনি বলেছেন", userTranscript)
        }
        if (assistantTranscript.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            MessageCard(assistantName, assistantTranscript)
        }
        Spacer(Modifier.height(20.dp))
        Text("Live audio-to-audio • বাংলা • English • Banglish", color = Color(0xFF70899B), fontSize = 11.sp)
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
    assistantName: String,
    onNameChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    geminiEnabled: Boolean,
    hasKey: Boolean,
    status: String,
    onBack: () -> Unit,
    onSaveName: () -> Unit,
    onSaveKey: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        TextButton(onClick = onBack) { Text("← Back", color = Color(0xFF8DDBF4)) }
        Text("Settings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text("Assistant identity", color = Color(0xFF55D9FF), fontWeight = FontWeight.SemiBold)
        OutlinedTextField(assistantName, onNameChange, modifier = Modifier.fillMaxWidth(), label = { Text("Assistant name") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSaveName, enabled = assistantName.trim().isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save assistant name") }
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Color(0xFF29465A))
        Spacer(Modifier.height(20.dp))
        Text("AI Provider", color = Color(0xFF55D9FF), fontWeight = FontWeight.SemiBold)
        Text("Gemini Live API", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (geminiEnabled) "Enabled" else "Disabled", color = Color(0xFFB8C9D8))
                Text(if (hasKey) "API key saved encrypted on this device" else "No API key saved", color = Color(0xFF8D9EAC), fontSize = 12.sp)
            }
            Switch(checked = geminiEnabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API Key") },
            placeholder = { Text(if (hasKey) "Saved. Enter a new key to replace" else "Paste your API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSaveKey, enabled = apiKey.trim().isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save API Key") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Create Gemini API Key") }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(status, color = Color(0xFF66D9A8), fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "API Key Android Keystore দিয়ে encrypt করে রাখা হয়। Live API-তে সরাসরি সংযোগের জন্য এই prototype-এ key ব্যবহার হয়। Production release-এর আগে short-lived token backend যোগ করতে হবে।",
            color = Color(0xFF8D9EAC), fontSize = 12.sp
        )
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

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12) { "সংরক্ষিত API key সঠিক নয়" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }
}
