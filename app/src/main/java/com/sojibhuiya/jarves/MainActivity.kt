package com.sojibhuiya.jarves

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.content.Context

private val Context.jarvesDataStore by preferencesDataStore(name = "jarves_settings")
private val ASSISTANT_NAME = stringPreferencesKey("assistant_name")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvesApp() }
    }
}

@Composable
private fun JarvesApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedName by context.jarvesDataStore.data
        .map { preferences -> preferences[ASSISTANT_NAME] }
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("JARVES", color = Color(0xFFEAF7FF), fontSize = 30.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                            Spacer(Modifier.height(20.dp))
                            Text("Loading your assistant…", color = Color(0xFFB8C9D8))
                        }
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
                            assistantName = savedName ?: "Jarves",
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
        Text("JARVES", color = Color(0xFFEAF7FF), fontSize = 30.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Spacer(Modifier.height(28.dp))
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
            placeholder = { Text("e.g. Jarves") },
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("JARVES", color = Color(0xFFEAF7FF), fontSize = 30.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Spacer(Modifier.height(36.dp))
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
        Text("Hello, I'm $assistantName", color = Color(0xFFEAF7FF),
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Your assistant is getting ready.", color = Color(0xFFB8C9D8), fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Text("Voice features are not connected yet.", color = Color(0xFF71869A), fontSize = 12.sp)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onEditName,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF12364D))
        ) {
            Text("Settings · Change assistant name", color = Color(0xFFBDEEFF))
        }
    }
}
