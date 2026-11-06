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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.util.Locale

private val Context.jarvesDataStore by preferencesDataStore(name = "jarves_settings")
private val ASSISTANT_NAME = stringPreferencesKey("assistant_name")

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
    val savedName by context.jarvesDataStore.data
        .map { preferences -> preferences[ASSISTANT_NAME].orEmpty() }
        .collectAsState(initial = null)

    var nameInput by remember(savedName) { mutableStateOf(savedName.orEmpty()) }
    var editingName by remember { mutableStateOf(false) }

    val background = Brush.verticalGradient(
        colors = listOf(Color(0xFF071321), Color(0xFF02060D))
    )

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF02060D)) {
            Box(
                modifier = Modifier.fillMaxSize().background(background).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    savedName == null -> {
                        Text("Loading your assistant…", color = Color(0xFFB8C9D8))
                    }
                    savedName.isNullOrBlank() || editingName -> {
                        NameSetup(
                            name = nameInput,
                            onNameChange = { nameInput = it },
                            onSave = {
                                val cleanName = nameInput.trim()
                                if (cleanName.isNotEmpty()) {
                                    scope.launch {
                                        context.jarvesDataStore.edit { it[ASSISTANT_NAME] = cleanName }
                                        editingName = false
                                    }
                                }
                            },
                            isFirstLaunch = savedName.isNullOrBlank()
                        )
                    }
                    else -> {
                        HomeScreen(
                            assistantName = savedName.orEmpty(),
                            onEditName = {
                                nameInput = savedName.orEmpty()
                                editingName = true
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NameSetup(
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    isFirstLaunch: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isFirstLaunch) "Give your assistant a name" else "Change assistant name",
            color = Color(0xFFEAF7FF), fontSize = 22.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "You can change this anytime in Settings.",
            color = Color(0xFF9FB3C5), fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Assistant name") },
            placeholder = { Text("Choose a name") },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF55D9FF),
                unfocusedBorderColor = Color(0xFF456174),
                focusedLabelColor = Color(0xFF55D9FF),
                unfocusedLabelColor = Color(0xFF9FB3C5),
                cursorColor = Color(0xFF55D9FF)
            )
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onSave,
            enabled = name.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF087FA5),
                contentColor = Color.White
            )
        ) {
            Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HomeScreen(assistantName: String, onEditName: () -> Unit) {
    val context = LocalContext.current
    var recognizedText by remember { mutableStateOf("") }
    var speechStatus by remember { mutableStateOf("মাইক্রোফোনে কথা বলতে নিচের বাটনে চাপ দিন") }
    var isListening by remember { mutableStateOf(false) }

    val ttsReady = remember { mutableStateOf(false) }
    val textToSpeech = remember(context) {
        TextToSpeech(context) { status ->
            ttsReady.value = status == TextToSpeech.SUCCESS
        }
    }

    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(textToSpeech) {
        onDispose {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    DisposableEffect(speechRecognizer) {
        if (speechRecognizer != null) {
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    speechStatus = "শুনছি… এখন কথা বলুন"
                }
                override fun onBeginningOfSpeech() {
                    speechStatus = "আপনার কথা শুনছি…"
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    speechStatus = "কথা বুঝে নিচ্ছি…"
                }
                override fun onError(error: Int) {
                    isListening = false
                    speechStatus = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোনের অনুমতি পাওয়া যায়নি"
                        SpeechRecognizer.ERROR_NO_MATCH -> "কথা বুঝতে পারিনি। আবার চেষ্টা করুন"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "নেটওয়ার্ক সমস্যা হয়েছে"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "একটু অপেক্ষা করে আবার চেষ্টা করুন"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কোনো কথা শোনা যায়নি"
                        else -> "ভয়েস ইনপুটে সমস্যা হয়েছে। আবার চেষ্টা করুন"
                    }
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    recognizedText = matches?.firstOrNull().orEmpty()
                    speechStatus = if (recognizedText.isNotBlank()) "আপনার কথাটি লেখা হয়েছে" else "কথা বুঝতে পারিনি। আবার চেষ্টা করুন"
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    partial?.firstOrNull()?.let { recognizedText = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startBanglaListening(speechRecognizer) { speechStatus = it; isListening = true }
        } else {
            speechStatus = "ভয়েস ইনপুটের জন্য মাইক্রোফোন অনুমতি দিতে হবে"
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.height(150.dp),
            shape = CircleShape,
            color = Color(0xFF0D2940),
            shadowElevation = 18.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("◉", color = Color(0xFF55D9FF), fontSize = 54.sp)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("হ্যালো! আমি $assistantName।", color = Color(0xFFEAF7FF),
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("কীভাবে আপনাকে সাহায্য করতে পারি?",
            color = Color(0xFFB8C9D8), fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (speechRecognizer == null) {
                    speechStatus = "এই ফোনে Speech Recognition service পাওয়া যায়নি"
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startBanglaListening(speechRecognizer) { speechStatus = it; isListening = true }
                } else {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = !isListening,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0B6685),
                contentColor = Color.White
            )
        ) {
            Text(if (isListening) "Listening…" else "🎙 Speak to Assistant", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))
        Text(speechStatus, color = Color(0xFF9FB3C5), fontSize = 13.sp)
        if (recognizedText.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF10283A)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("আপনি বলেছেন:", color = Color(0xFF55D9FF), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(recognizedText, color = Color.White, fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                val banglaLocale = Locale("bn", "BD")
                val languageResult = textToSpeech.setLanguage(banglaLocale)
                val greeting = if (languageResult < TextToSpeech.LANG_AVAILABLE) {
                    textToSpeech.language = Locale.US
                    "Hello! I'm $assistantName. How can I help you?"
                } else {
                    "হ্যালো! আমি $assistantName। কীভাবে আপনাকে সাহায্য করতে পারি?"
                }
                textToSpeech.speak(greeting, TextToSpeech.QUEUE_FLUSH, null, "assistant_greeting")
            },
            enabled = ttsReady.value,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF087FA5),
                contentColor = Color.White
            )
        ) {
            Text("Speak Greeting", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!ttsReady.value) {
            Spacer(Modifier.height(8.dp))
            Text("Preparing phone voice…", color = Color(0xFF71869A), fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onEditName,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF12364D))
        ) {
            Text("Settings · Change assistant name", color = Color(0xFFBDEEFF))
        }
    }
}

private fun startBanglaListening(
    recognizer: SpeechRecognizer?,
    onStarted: (String) -> Unit
) {
    if (recognizer == null) {
        onStarted("এই ফোনে Speech Recognition service পাওয়া যায়নি")
        return
    }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "বাংলায় কথা বলুন")
    }
    try {
        recognizer.startListening(intent)
        onStarted("মাইক্রোফোন চালু হচ্ছে…")
    } catch (_: Exception) {
        onStarted("ভয়েস ইনপুট শুরু করা যায়নি। আবার চেষ্টা করুন")
    }
}
