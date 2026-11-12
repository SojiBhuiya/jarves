package com.sojibhuiya.jarves

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.sojibhuiya.jarves.apiproviders.ApiProviderRecord
import com.sojibhuiya.jarves.apiproviders.ApiProviderResult
import com.sojibhuiya.jarves.apiproviders.CredentialResult
import com.sojibhuiya.jarves.apiproviders.GeminiLiveAdapter
import com.sojibhuiya.jarves.apiproviders.MigrationResult
import com.sojibhuiya.jarves.chat.ChatSource
import com.sojibhuiya.jarves.intelligence.GroundedSearchClient
import com.sojibhuiya.jarves.intelligence.GroundingAvailability
import com.sojibhuiya.jarves.intelligence.LiveState
import com.sojibhuiya.jarves.intelligence.LiveStateRouting
import com.sojibhuiya.jarves.chat.Conversation
import com.sojibhuiya.jarves.storage.ApiProvidersModule
import com.sojibhuiya.jarves.storage.ConversationModule
import com.sojibhuiya.jarves.ui.chats.ChatsScreen
import com.sojibhuiya.jarves.storage.PrefKeys
import com.sojibhuiya.jarves.storage.jarvesDataStore
import com.sojibhuiya.jarves.ui.apiproviders.ApiProvidersScreen
import com.sojibhuiya.jarves.ui.components.JarvesBackRow
import com.sojibhuiya.jarves.ui.components.JarvesCard
import com.sojibhuiya.jarves.ui.components.JarvesIcon
import com.sojibhuiya.jarves.ui.components.JarvesIconButton
import com.sojibhuiya.jarves.ui.components.JarvesIconTextAction
import com.sojibhuiya.jarves.ui.components.JarvesNavigation
import com.sojibhuiya.jarves.ui.components.JarvesStatusLine
import com.sojibhuiya.jarves.ui.components.JarvesTextAction
import com.sojibhuiya.jarves.ui.components.JarvesTextField
import com.sojibhuiya.jarves.ui.components.NavigationDrawer
import com.sojibhuiya.jarves.ui.components.SectionLabel
import com.sojibhuiya.jarves.ui.icons.JarvesIcons
import com.sojibhuiya.jarves.ui.theme.JarvesColors
import com.sojibhuiya.jarves.ui.theme.JarvesDimens
import kotlinx.coroutines.launch

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
    val repository = remember { ApiProvidersModule.repository(context) }
    val prefs by context.jarvesDataStore.data.collectAsState(initial = null)
    var screen by remember { mutableStateOf(JarvesNavigation.HOME) }
    var assistantNameInput by remember(prefs) { mutableStateOf(prefs?.get(PrefKeys.ASSISTANT_NAME).orEmpty()) }
    var status by remember { mutableStateOf("মাঝের বাটনে চাপ দিয়ে Live Voice চালু করুন") }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf<GeminiLiveVoice?>(null) }
    var providers by remember { mutableStateOf<List<ApiProviderRecord>>(emptyList()) }
    var providersLoaded by remember { mutableStateOf(false) }

    // ---- Chats: conversations live in their own store; only ids are held in the UI layer.
    val conversationRepository = remember { ConversationModule.repository(context) }
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var activeConversationId by remember { mutableStateOf<String?>(null) }
    var selectedConversationId by remember { mutableStateOf<String?>(null) }
    val userBuffer = remember { StringBuilder() }
    val assistantBuffer = remember { StringBuilder() }
    // Web sources returned by Google Search grounding for the current assistant turn.
    var pendingSources by remember { mutableStateOf<List<ChatSource>>(emptyList()) }
    var flushJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Android Back: close the drawer first, then walk back up the screen stack.
    BackHandler(
        enabled = drawerState.isOpen || screen != JarvesNavigation.HOME ||
            (screen == JarvesNavigation.CHATS && selectedConversationId != null)
    ) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            screen == JarvesNavigation.CHATS && selectedConversationId != null ->
                selectedConversationId = null
            screen == JarvesNavigation.API_PROVIDERS -> screen = JarvesNavigation.SETTINGS
            else -> screen = JarvesNavigation.HOME
        }
    }

    suspend fun reloadProviders() {
        when (val loaded = repository.load()) {
            is ApiProviderResult.Success -> providers = loaded.records
            else -> Unit // keep the previous list; storage errors surface in the providers screen
        }
        providersLoaded = true
    }

    /** Persists a finished turn into the active conversation (transcripts are no longer shown). */
    fun flushTranscript(fromUser: Boolean) {
        val buffer = if (fromUser) userBuffer else assistantBuffer
        val text = buffer.toString().trim()
        buffer.setLength(0)
        if (text.isEmpty()) return
        val conversationId = activeConversationId ?: return
        val sources = if (fromUser) emptyList() else pendingSources
        if (!fromUser) pendingSources = emptyList()
        scope.launch {
            val last = conversations.firstOrNull { it.id == conversationId }?.messages?.lastOrNull()
            if (last != null && last.text == text && last.fromUser == fromUser) return@launch
            conversationRepository.appendMessage(conversationId, text, fromUser, sources)?.let { conversations = it }
        }
    }

    /** Live chunks arrive incrementally: accumulate per speaker and flush when they switch/idle. */
    fun onTranscriptChunk(text: String, isAssistant: Boolean) {
        if (text.isBlank()) return
        if (isAssistant) {
            if (userBuffer.isNotEmpty()) flushTranscript(fromUser = true)
            assistantBuffer.append(text)
        } else {
            if (assistantBuffer.isNotEmpty()) flushTranscript(fromUser = false)
            userBuffer.append(text)
        }
        flushJob?.cancel()
        flushJob = scope.launch {
            kotlinx.coroutines.delay(1200)
            flushTranscript(fromUser = !isAssistant)
        }
    }

    /**
     * Design 4 (0.2.4): writes the search failure report into the active conversation as its own
     * message, so the real HTTP status and Google's own error text are visible and selectable in
     * the chat instead of only being spoken by the model. Any speech still buffered for this turn
     * is flushed first, so the report never lands in the middle of a sentence.
     */
    fun appendSearchReport(report: String) {
        if (report.isBlank()) return
        flushTranscript(fromUser = true)
        flushTranscript(fromUser = false)
        val conversationId = activeConversationId ?: return
        scope.launch {
            conversationRepository.appendMessage(conversationId, report, fromUser = false)
                ?.let { conversations = it }
        }
    }

    /**
     * New Chat: a brand new conversation with its own id, plus a stopped voice session so the next
     * Gemini Live connection starts without the previous conversation's context.
     */
    fun startNewChat() {
        engine?.stop()
        engine = null
        connected = false
        connecting = false
        userBuffer.setLength(0)
        assistantBuffer.setLength(0)
        scope.launch {
            val updated = conversationRepository.newConversation()
            conversations = updated
            val newId = updated.firstOrNull { it.isEmpty }?.id ?: updated.firstOrNull()?.id
            activeConversationId = newId
            selectedConversationId = newId
            screen = JarvesNavigation.CHATS
        }
    }

    // One-time migration of the pre-provider-store Gemini key, then the initial list load.
    LaunchedEffect(Unit) {
        when (val migration = ApiProvidersModule.migration(context).migrate()) {
            is MigrationResult.Migrated ->
                status = "আগের Gemini API Key API Providers-এ নিরাপদে স্থানান্তরিত হয়েছে"
            is MigrationResult.Failed ->
                status = migration.message
            else -> Unit
        }
        reloadProviders()
    }

    // Load saved chats once, and make sure there is always an active conversation to write to.
    LaunchedEffect(Unit) {
        val stored = conversationRepository.load()
        val list = if (stored.isEmpty()) conversationRepository.newConversation() else stored
        conversations = list
        activeConversationId = list.firstOrNull()?.id
    }

    val assistantName = prefs?.get(PrefKeys.ASSISTANT_NAME).orEmpty()
    val geminiRecord = providers.firstOrNull { it.providerId == GeminiLiveAdapter.PROVIDER_ID }
    val legacyEncryptedKey = prefs?.get(PrefKeys.GEMINI_KEY_ENCRYPTED).orEmpty()
    val geminiEnabled = geminiRecord?.enabled ?: (prefs?.get(PrefKeys.GEMINI_ENABLED) ?: false)

    // API Providers is the single place where Gemini Live is enabled/disabled. Keep the legacy
    // preference (used only as a fallback) in sync and end any live session when it is disabled.
    LaunchedEffect(geminiRecord?.id, geminiRecord?.enabled) {
        val record = geminiRecord ?: return@LaunchedEffect
        if ((prefs?.get(PrefKeys.GEMINI_ENABLED) ?: false) != record.enabled) {
            context.jarvesDataStore.edit { it[PrefKeys.GEMINI_ENABLED] = record.enabled }
        }
        if (!record.enabled && engine != null) {
            engine?.stop()
            engine = null
            connected = false
            connecting = false
        }
    }
    val hasKey = geminiRecord?.hasCredential == true || legacyEncryptedKey.isNotBlank()

    /**
     * F6 — a deliberate credential/configuration change is the moment to re-check grounding.
     * Saving a different key (or editing the saved record) clears the remembered "search
     * unavailable" result, so the next session asks Google for live search again. Nothing is
     * written, read or logged here: the memo is memory-only.
     */
    LaunchedEffect(geminiRecord?.id, geminiRecord?.updatedAt, hasKey) {
        GroundingAvailability.shared.recheck()
    }

    /** Last-resort decrypt of the legacy preference if it was never migrated. */
    suspend fun legacyFallbackKey(): String? {
        val blob = legacyEncryptedKey.takeIf { it.isNotBlank() } ?: return null
        return runCatching { ApiProvidersModule.credentialCipher().decrypt(blob) }.getOrNull()
            ?: runCatching { ApiProvidersModule.legacyCipher().decrypt(blob) }.getOrNull()
    }

    fun startLive() {
        if (engine != null || connecting) return
        if (!geminiEnabled) { status = "API Providers থেকে Gemini চালু করুন"; return }
        if (!hasKey) { status = "API Providers থেকে Gemini Key যোগ করুন"; return }
        connecting = true
        scope.launch {
            val credentialKey = when (val credential = repository.resolveGeminiLiveCredential()) {
                is CredentialResult.Success -> credential.apiKey
                is CredentialResult.Failure -> legacyFallbackKey()
            }
            if (credentialKey.isNullOrBlank()) {
                status = "সংরক্ষিত API Key পড়া যায়নি। নতুন Key দিন।"
                connecting = false
                return@launch
            }
            val live = GeminiLiveVoice(
                apiKey = credentialKey,
                assistantName = assistantName,
                // Design 4: the app performs the search over REST with the same key, so the live
                // session never has to declare the Live grounding tool.
                searchClient = GroundedSearchClient(apiKey = credentialKey),
                // Status text is for the user to read only. State is never inferred from it.
                onStatus = { value -> scope.launch { status = value } },
                // Explicit state: the single source of truth for these booleans and for releasing
                // the engine. No Bengali substring matching anywhere.
                onState = { state ->
                    scope.launch {
                        val next = LiveStateRouting.route(state)
                        connected = next.connected
                        connecting = next.connecting
                        if (next.releaseEngine) {
                            engine = null
                            if (state == LiveState.ENDED) {
                                flushTranscript(fromUser = true)
                                flushTranscript(fromUser = false)
                            }
                        }
                    }
                },
                onTranscript = { text, isAssistant -> onTranscriptChunk(text, isAssistant) },
                onGrounding = { sources ->
                    pendingSources = sources.map { ChatSource(title = it.title, url = it.url) }
                },
                onSearchReport = { report -> scope.launch { appendSearchReport(report) } },
                onError = { message ->
                    scope.launch {
                        status = message
                        connected = false
                        connecting = false
                        engine = null
                    }
                }
            )
            engine = live
            live.start()
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLive() else status = "Live Voice ব্যবহারের জন্য Microphone permission দিন"
    }

    val latestEngine = rememberUpdatedState(engine)
    DisposableEffect(Unit) {
        onDispose { latestEngine.value?.stop() }
    }

    val background = Brush.verticalGradient(listOf(Color(0xFF071321), Color(0xFF02060D)))
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF55D9FF), background = Color(0xFF02060D))) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF02060D)) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = true,
                scrimColor = Color.Black.copy(alpha = 0.55f),
                drawerContent = {
                    NavigationDrawer(
                        assistantName = assistantName,
                        currentRoute = screen,
                        onNavigate = { route ->
                            screen = route
                            scope.launch { drawerState.close() }
                        },
                        onClose = { scope.launch { drawerState.close() } }
                    )
                }
            ) {
                Box(Modifier.fillMaxSize().background(background)) {
                    when {
                        prefs == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        assistantName.isBlank() -> NameSetup(
                            value = assistantNameInput,
                            onChange = { assistantNameInput = it },
                            onSave = {
                                scope.launch { context.jarvesDataStore.edit { it[PrefKeys.ASSISTANT_NAME] = assistantNameInput.trim() } }
                            }
                        )
                        screen == JarvesNavigation.API_PROVIDERS -> ApiProvidersScreen(
                            repository = repository,
                            onBack = { screen = JarvesNavigation.SETTINGS },
                            onRecordsChanged = { updated ->
                                providers = updated
                                providersLoaded = true
                            }
                        )
                        screen == JarvesNavigation.CHATS -> ChatsScreen(
                            conversations = conversations,
                            selectedConversationId = selectedConversationId,
                            onSelectConversation = { id ->
                                selectedConversationId = id
                                activeConversationId = id
                            },
                            onNewChat = { startNewChat() },
                            onBack = {
                                selectedConversationId = null
                                screen = JarvesNavigation.HOME
                            }
                        )
                        screen == JarvesNavigation.SETTINGS -> SettingsScreen(
                            assistantName = assistantNameInput.ifBlank { assistantName },
                            status = status,
                            providersLoaded = providersLoaded,
                            providerCount = providers.size,
                            enabledProviderCount = providers.count { it.enabled },
                            onBack = { screen = JarvesNavigation.HOME },
                            onOpenApiProviders = { screen = JarvesNavigation.API_PROVIDERS },
                            onSaveName = { newName ->
                                scope.launch {
                                    context.jarvesDataStore.edit { it[PrefKeys.ASSISTANT_NAME] = newName }
                                    assistantNameInput = newName
                                    status = "Assistant-এর নাম সংরক্ষণ হয়েছে"
                                }
                            }
                        )
                        else -> HomeScreen(
                            assistantName = assistantName,
                            geminiEnabled = geminiEnabled,
                            hasKey = hasKey,
                            connected = connected,
                            connecting = connecting,
                            status = status,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onVoiceClick = {
                                if (engine != null) {
                                    flushTranscript(fromUser = true)
                                    flushTranscript(fromUser = false)
                                    engine?.stop()
                                    engine = null
                                    connected = false
                                    connecting = false
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
}

@Composable
private fun NameSetup(value: String, onChange: (String) -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Give your assistant a name", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("এই নামটি অ্যাপের ভেতরে Assistant-এর পরিচয় হবে।", color = Color(0xFFB8C9D8))
        Spacer(Modifier.height(20.dp))
        JarvesTextField(
            value = value,
            onValueChange = onChange,
            placeholder = "Assistant name",
            modifier = Modifier.fillMaxWidth()
        )
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
    connecting: Boolean,
    status: String,
    onOpenDrawer: () -> Unit,
    onVoiceClick: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // Fixed top app bar: the menu icon never scrolls away from the Home screen.
        HomeTopBar(onOpenDrawer = onOpenDrawer)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Box(
            Modifier.size(166.dp)
                .background(if (connected) Color(0xFF12465A) else Color(0xFF0D2940), CircleShape)
                .clickable(onClick = onVoiceClick),
            contentAlignment = Alignment.Center
        ) {
            Text(if (connected) "◉" else if (connecting) "…" else "◎", color = Color(0xFF55D9FF), fontSize = 76.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text("হ্যালো! আমি $assistantName।", color = Color(0xFFEAF7FF), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Text("কীভাবে আপনাকে সাহায্য করতে পারি?", color = Color(0xFFB8C9D8), fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            when { !geminiEnabled -> "Gemini: OFF"; !hasKey -> "Gemini API Key প্রয়োজন"; connected -> "LIVE VOICE CONNECTED"; connecting -> "CONNECTING TO GEMINI LIVE"; else -> "Gemini Live প্রস্তুত" },
            color = if (connected) Color(0xFF66D9A8) else Color(0xFFFFC36B),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onVoiceClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(
                imageVector = if (connected || connecting) JarvesIcons.X else JarvesIcons.Mic,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(if (connected || connecting) "Stop Live Voice" else "Start Live Conversation")
        }
        Spacer(Modifier.height(12.dp))
        SelectableText(status, color = Color(0xFF9FB3C5))
        Spacer(Modifier.height(20.dp))
        Text("Live audio-to-audio • বাংলা • English • Banglish", color = Color(0xFF70899B), fontSize = 11.sp)
        }
    }
}

/** Minimal fixed top bar holding only the two-line menu icon. */
@Composable
private fun HomeTopBar(onOpenDrawer: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 22.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JarvesIconButton(
            icon = JarvesIcons.Menu,
            contentDescription = "Open navigation",
            onClick = onOpenDrawer,
            tint = Color(0xFFB8C9D8)
        )
    }
}

@Composable
private fun SelectableText(value: String, color: Color) {
    SelectionContainer {
        Text(value, color = color, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
}

@Composable
private fun SettingsScreen(
    assistantName: String,
    status: String,
    providersLoaded: Boolean,
    providerCount: Int,
    enabledProviderCount: Int,
    onBack: () -> Unit,
    onOpenApiProviders: () -> Unit,
    onSaveName: (String) -> Unit
) {
    var editingName by remember(assistantName) { mutableStateOf(assistantName.isBlank()) }
    var draftName by remember(assistantName) { mutableStateOf(assistantName) }
    // Needed by the copy action on the diagnostic line below.
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(JarvesColors.Background)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvesDimens.ScreenPadding)
    ) {
        Spacer(Modifier.height(4.dp))
        JarvesBackRow(onBack = onBack)

        Text(
            text = "Settings",
            color = JarvesColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp, bottom = 18.dp)
        )

        SectionLabel("Assistant")
        Spacer(Modifier.height(8.dp))
        JarvesCard {
            Column(Modifier.padding(JarvesDimens.CardPadding)) {
                if (editingName) {
                    // ---- Edit mode: input + Save / Cancel
                    JarvesTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        placeholder = "Assistant name"
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (assistantName.isNotBlank()) {
                            JarvesTextAction(
                                text = "Cancel",
                                color = JarvesColors.TextSecondary,
                                onClick = {
                                    draftName = assistantName
                                    editingName = false
                                }
                            )
                        }
                        JarvesTextAction(
                            text = "Save",
                            onClick = {
                                onSaveName(draftName.trim())
                                editingName = false
                            },
                            enabled = draftName.trim().isNotEmpty()
                        )
                    }
                } else {
                    // ---- Display mode: saved name + pencil Edit action
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = JarvesDimens.TouchTarget),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = assistantName,
                            color = JarvesColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        JarvesIconTextAction(
                            icon = JarvesIcons.Pencil,
                            label = "Edit",
                            onClick = {
                                draftName = assistantName
                                editingName = true
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(JarvesDimens.SectionGap))
        SectionLabel("Integrations")
        Spacer(Modifier.height(8.dp))
        JarvesCard {
            ApiProvidersRow(
                providerCount = providerCount,
                enabledCount = enabledProviderCount,
                loaded = providersLoaded,
                onClick = onOpenApiProviders
            )
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            // 0.2.4: the diagnostic block appears on this line; make it copyable in one tap.
            JarvesStatusLine(
                text = status,
                isError = false,
                onCopy = { copyToClipboard(context, "Jarves diagnostics", status) }
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "API providers and their keys are managed in API Providers. Keys are encrypted " +
                "with Android Keystore (AES-GCM) and never shown in full.",
            color = JarvesColors.TextMuted,
            fontSize = 10.5.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Navigation row to the single place where providers and credentials are managed. */
@Composable
private fun ApiProvidersRow(
    providerCount: Int,
    enabledCount: Int,
    loaded: Boolean,
    onClick: () -> Unit
) {
    val subtitle = when {
        !loaded -> "Loading…"
        providerCount == 0 -> "No providers added"
        else -> "$providerCount saved · $enabledCount enabled"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open API Providers", onClick = onClick)
            .heightIn(min = JarvesDimens.RowMinHeight)
            .padding(horizontal = JarvesDimens.CardPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(JarvesColors.SurfaceMuted)
                .border(1.dp, JarvesColors.Border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = JarvesIcons.KeyRound,
                contentDescription = null,
                tint = JarvesColors.Accent,
                modifier = Modifier.size(JarvesDimens.IconSmall)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "API Providers",
                color = JarvesColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(1.dp))
            Text(text = subtitle, color = JarvesColors.TextMuted, fontSize = 11.sp)
        }
        Icon(
            imageVector = JarvesIcons.ChevronRight,
            contentDescription = "Open API Providers",
            tint = JarvesColors.TextMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Puts [text] on the system clipboard. Used by the diagnostic line so the user can send the exact
 * on-device numbers without retyping them. The API key never reaches this text: everything that
 * arrives here has already been scrubbed by the search client.
 */
private fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    runCatching {
        val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        manager.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }
}
