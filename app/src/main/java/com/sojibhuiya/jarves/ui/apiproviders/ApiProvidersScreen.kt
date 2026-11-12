package com.sojibhuiya.jarves.ui.apiproviders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sojibhuiya.jarves.apiproviders.ApiCategory
import com.sojibhuiya.jarves.apiproviders.ApiProviderField
import com.sojibhuiya.jarves.apiproviders.ApiProviderLimits
import com.sojibhuiya.jarves.apiproviders.ApiProviderRecord
import com.sojibhuiya.jarves.apiproviders.ApiProviderRepository
import com.sojibhuiya.jarves.apiproviders.ApiProviderResult
import com.sojibhuiya.jarves.apiproviders.IntegrationStatus
import com.sojibhuiya.jarves.ui.components.CompactAddButton
import com.sojibhuiya.jarves.ui.components.CompactSwitch
import com.sojibhuiya.jarves.ui.components.IconAction
import com.sojibhuiya.jarves.ui.components.JarvesBackRow
import com.sojibhuiya.jarves.ui.components.JarvesCard
import com.sojibhuiya.jarves.ui.components.JarvesPrimaryButton
import com.sojibhuiya.jarves.ui.components.JarvesSecondaryButton
import com.sojibhuiya.jarves.ui.components.JarvesTextAction
import com.sojibhuiya.jarves.ui.components.JarvesTextField
import com.sojibhuiya.jarves.ui.icons.JarvesIcons
import com.sojibhuiya.jarves.ui.theme.JarvesColors
import com.sojibhuiya.jarves.ui.theme.JarvesDimens
import kotlinx.coroutines.launch

/**
 * API Providers management surface.
 *
 * Persistence is delegated to [ApiProviderRepository]: every action replaces the visible list
 * with the freshly persisted records, so list, counters and storage cannot disagree. The
 * provider's credential is only ever shown masked; the edit form never decrypts a stored key.
 */
@Composable
fun ApiProvidersScreen(
    repository: ApiProviderRepository,
    onBack: () -> Unit,
    onRecordsChanged: (List<ApiProviderRecord>) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<ApiProviderRecord>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ApiProviderRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<ApiProviderRecord?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun publish(list: List<ApiProviderRecord>) {
        records = list
        loadError = null
        onRecordsChanged(list)
    }

    fun handle(result: ApiProviderResult, successMessage: String) {
        when (result) {
            is ApiProviderResult.Success -> {
                publish(result.records)
                message = successMessage
                messageIsError = false
            }
            is ApiProviderResult.Failed -> {
                message = result.message
                messageIsError = true
            }
            is ApiProviderResult.ValidationFailed -> {
                message = result.message
                messageIsError = true
            }
        }
    }

    suspend fun refresh() {
        when (val result = repository.load()) {
            is ApiProviderResult.Success -> publish(result.records)
            is ApiProviderResult.Failed -> {
                records = emptyList()
                loadError = result.message
            }
            is ApiProviderResult.ValidationFailed -> {
                records = emptyList()
                loadError = result.message
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(4000)
            message = null
        }
    }

    fun openAddForm() {
        editing = null
        showForm = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(JarvesColors.Background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(horizontal = JarvesDimens.ScreenPadding)
        ) {
            Spacer(Modifier.height(4.dp))
            JarvesBackRow(onBack = onBack)

            // ---- Header: title + dynamic counts on the left, compact "+ Add" on the right ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "API Providers",
                        color = JarvesColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = totalLabel(records),
                        color = JarvesColors.TextMuted,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.5.dp,
                        color = JarvesColors.TextMuted
                    )
                    Spacer(Modifier.width(10.dp))
                }
                CompactAddButton(onClick = ::openAddForm)
            }

            if (message != null) {
                MessageBanner(
                    text = message.orEmpty(),
                    isError = messageIsError,
                    onDismiss = { message = null }
                )
                Spacer(Modifier.height(10.dp))
            }

            when {
                records == null -> LoadingRow()
                loadError != null -> LoadErrorCard(
                    message = loadError.orEmpty(),
                    onRetry = { scope.launch { busy = true; refresh(); busy = false } }
                )
                records.orEmpty().isEmpty() -> EmptyState(onAdd = ::openAddForm)
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(JarvesDimens.ItemGap)
                    ) {
                        items(records.orEmpty(), key = { it.id }) { record ->
                            ProviderCard(
                                record = record,
                                integration = repository.integrationStatus(record),
                                onToggle = { enabled ->
                                    scope.launch {
                                        busy = true
                                        val result = repository.setEnabled(record.id, enabled)
                                        busy = false
                                        handle(
                                            result,
                                            if (enabled) "${record.name} enabled" else "${record.name} disabled"
                                        )
                                    }
                                },
                                onEdit = {
                                    editing = record
                                    showForm = true
                                },
                                onDelete = { pendingDelete = record }
                            )
                        }
                        item {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Keys are encrypted with Android Keystore (AES-GCM) before they are saved. " +
                                    "Only integrated providers can be used by Jarves today.",
                                color = JarvesColors.TextMuted,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showForm) {
        ApiProviderFormDialog(
            existing = editing,
            onDismiss = { showForm = false },
            onSave = { name, apiKey, category, finish ->
                scope.launch {
                    busy = true
                    val current = editing
                    val result = if (current == null) {
                        repository.add(name, apiKey, category)
                    } else {
                        repository.update(current.id, name, category, apiKey.ifBlank { null })
                    }
                    busy = false
                    when (result) {
                        is ApiProviderResult.Success -> {
                            finish(null)
                            showForm = false
                            publish(result.records)
                            message = if (current == null) "$name added" else "$name updated"
                            messageIsError = false
                        }
                        is ApiProviderResult.ValidationFailed -> finish(fieldError(result.field, result.message))
                        is ApiProviderResult.Failed -> finish(result.message)
                    }
                }
            }
        )
    }

    pendingDelete?.let { record ->
        DeleteConfirmationDialog(
            record = record,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    busy = true
                    val result = repository.delete(record.id)
                    busy = false
                    handle(result, "${record.name} deleted")
                }
            }
        )
    }
}

/** "1 saved API · 1 enabled" — always derived from the persisted list. */
private fun totalLabel(records: List<ApiProviderRecord>?): String {
    if (records == null) return "Loading…"
    val total = records.size
    if (total == 0) return "No API providers yet"
    val enabled = records.count { it.enabled }
    val apiWord = if (total == 1) "saved API" else "saved APIs"
    return "$total $apiWord · $enabled enabled"
}

private fun fieldError(field: ApiProviderField, message: String): String = when (field) {
    ApiProviderField.NAME -> "name:$message"
    ApiProviderField.API_KEY -> "key:$message"
    ApiProviderField.CATEGORY -> "category:$message"
}

private fun ApiCategory.icon(): ImageVector =
    if (this == ApiCategory.VOICE) JarvesIcons.Mic else JarvesIcons.Bot

// ---------------------------------------------------------------------------- states

@Composable
private fun MessageBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
            .background(if (isError) JarvesColors.DangerSoft else JarvesColors.SuccessSoft)
            .clickable(onClickLabel = "Dismiss message", onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = if (isError) JarvesColors.Danger else JarvesColors.Success,
            fontSize = 11.5.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = JarvesIcons.X,
            contentDescription = null,
            tint = JarvesColors.TextMuted,
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.5.dp,
            color = JarvesColors.Accent
        )
        Spacer(Modifier.width(10.dp))
        Text("Loading saved providers…", color = JarvesColors.TextMuted, fontSize = 11.5.sp)
    }
}

@Composable
private fun LoadErrorCard(message: String, onRetry: () -> Unit) {
    JarvesCard(modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(JarvesDimens.CardPadding)) {
            Text(message, color = JarvesColors.Danger, fontSize = 11.5.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(4.dp))
            JarvesTextAction("Retry", onClick = onRetry, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    JarvesCard(modifier = Modifier.padding(top = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = JarvesIcons.KeyRound,
                contentDescription = null,
                tint = JarvesColors.TextMuted,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "No API providers added yet",
                color = JarvesColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Add a Voice API or General AI API to get started.",
                color = JarvesColors.TextMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(14.dp))
            CompactAddButton(onClick = onAdd, label = "Add")
        }
    }
}

// ---------------------------------------------------------------------------- card

@Composable
private fun ProviderCard(
    record: ApiProviderRecord,
    integration: IntegrationStatus,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    JarvesCard {
        Column(Modifier.padding(JarvesDimens.CardPadding)) {
            // Row 1: icon · name · the single enable/disable control
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(record.category)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = record.name,
                    color = if (record.enabled) JarvesColors.TextPrimary else JarvesColors.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                CompactSwitch(
                    checked = record.enabled,
                    onCheckedChange = onToggle,
                    contentDescription = if (record.enabled) {
                        "Disable ${record.name}"
                    } else {
                        "Enable ${record.name}"
                    },
                )
            }

            Spacer(Modifier.height(5.dp))

            // Row 2: category · integration status (state is conveyed by the switch, not text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.category.label, color = JarvesColors.TextMuted, fontSize = 11.sp)
                Text(" · ", color = JarvesColors.TextMuted, fontSize = 11.sp)
                Text(
                    text = integration.label,
                    color = if (integration == IntegrationStatus.INTEGRATED) {
                        JarvesColors.Success
                    } else {
                        JarvesColors.TextMuted
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Row 3: masked key + compact icon actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.maskedApiKey(),
                    color = JarvesColors.TextSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconAction(
                    icon = JarvesIcons.Pencil,
                    contentDescription = "Edit ${record.name}",
                    onClick = onEdit,
                    tint = JarvesColors.TextSecondary
                )
                // Clear gap so the two 44 dp surfaces never touch or visually merge.
                Spacer(Modifier.width(JarvesDimens.ButtonGap))
                IconAction(
                    icon = JarvesIcons.Trash,
                    contentDescription = "Delete ${record.name}",
                    onClick = onDelete,
                    tint = JarvesColors.Danger,
                    background = JarvesColors.ButtonDanger,
                    borderColor = JarvesColors.ButtonDangerBorder
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(category: ApiCategory) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(JarvesColors.SurfaceMuted)
            .border(1.dp, JarvesColors.Border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = category.icon(),
            contentDescription = null,
            tint = if (category == ApiCategory.VOICE) JarvesColors.Accent else JarvesColors.TextSecondary,
            modifier = Modifier.size(14.dp)
        )
    }
}

// ---------------------------------------------------------------------------- dialogs

@Composable
private fun DeleteConfirmationDialog(
    record: ApiProviderRecord,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(JarvesDimens.CardRadius))
                .background(JarvesColors.Surface)
                .border(1.dp, JarvesColors.Border, RoundedCornerShape(JarvesDimens.CardRadius))
                .padding(18.dp)
        ) {
            Text(
                "Delete ${record.name}?",
                color = JarvesColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This removes the saved provider and its encrypted API key from this device. This cannot be undone.",
                color = JarvesColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                JarvesTextAction("Cancel", onClick = onDismiss, color = JarvesColors.TextSecondary)
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .height(JarvesDimens.HeaderButtonHeight)
                        .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
                        .background(JarvesColors.DangerSoft)
                        .border(1.dp, JarvesColors.Danger.copy(alpha = 0.45f), RoundedCornerShape(JarvesDimens.ControlRadius))
                        .clickable(onClickLabel = "Delete", onClick = onConfirm)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Delete", color = JarvesColors.Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Add / Edit form.
 *
 * In edit mode the key field starts empty: a stored credential is never decrypted for display,
 * and leaving the field blank keeps the existing key.
 */
@Composable
private fun ApiProviderFormDialog(
    existing: ApiProviderRecord?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        apiKey: String,
        category: ApiCategory,
        finish: (String?) -> Unit
    ) -> Unit
) {
    val isEdit = existing != null
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(existing?.category ?: ApiCategory.VOICE) }
    var keyVisible by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .imePadding()
                .heightIn(max = 580.dp)
                .clip(RoundedCornerShape(JarvesDimens.CardRadius))
                .background(JarvesColors.Surface)
                .border(1.dp, JarvesColors.Border, RoundedCornerShape(JarvesDimens.CardRadius))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = if (isEdit) "Edit API" else "Add API",
                color = JarvesColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (isEdit) "Update the name, type or key." else "Connect a voice or general AI provider.",
                color = JarvesColors.TextMuted,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("API NAME")
            Spacer(Modifier.height(6.dp))
            JarvesTextField(
                value = name,
                onValueChange = {
                    name = it.take(ApiProviderLimits.MAX_NAME_LENGTH)
                    nameError = null
                },
                placeholder = "Gemini Live, OpenAI, My Custom API…",
                isError = nameError != null,
                supportingText = nameError
            )

            Spacer(Modifier.height(12.dp))
            FieldLabel(if (isEdit) "REPLACE API KEY" else "API KEY")
            Spacer(Modifier.height(6.dp))
            JarvesTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    keyError = null
                },
                placeholder = if (isEdit) "Leave blank to keep the saved key" else "Paste the API key",
                isError = keyError != null,
                supportingText = keyError,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailing = {
                    JarvesTextAction(
                        text = if (keyVisible) "Hide" else "Show",
                        onClick = { keyVisible = !keyVisible }
                    )
                }
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("API TYPE")
            Spacer(Modifier.height(6.dp))
            ApiCategory.entries.forEach { option ->
                CategoryOptionRow(
                    category = option,
                    selected = category == option,
                    onClick = { category = option }
                )
                Spacer(Modifier.height(6.dp))
            }

            formError?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = JarvesColors.Danger, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                JarvesSecondaryButton(
                    text = "Cancel",
                    onClick = { if (!saving) onDismiss() }
                )
                Spacer(Modifier.width(8.dp))
                JarvesPrimaryButton(
                    text = if (saving) "Saving…" else "Save",
                    enabled = !saving,
                    onClick = {
                        nameError = null
                        keyError = null
                        formError = null
                        if (name.trim().isEmpty()) {
                            nameError = "API Name is required"
                            return@JarvesPrimaryButton
                        }
                        if (!isEdit && apiKey.trim().isEmpty()) {
                            keyError = "API Key is required"
                            return@JarvesPrimaryButton
                        }
                        saving = true
                        onSave(name, apiKey, category) { error ->
                            saving = false
                            if (error != null) {
                                when {
                                    error.startsWith("name:") -> nameError = error.removePrefix("name:")
                                    error.startsWith("key:") -> keyError = error.removePrefix("key:")
                                    error.startsWith("category:") -> formError = error.removePrefix("category:")
                                    else -> formError = error
                                }
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Keys are encrypted with Android Keystore (AES-GCM) and are never shown in full.",
                color = JarvesColors.TextMuted,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = JarvesColors.TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun CategoryOptionRow(category: ApiCategory, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
            .background(if (selected) JarvesColors.AccentSoft else JarvesColors.ButtonNeutral)
            .border(
                width = 1.dp,
                color = if (selected) JarvesColors.Accent.copy(alpha = 0.55f) else JarvesColors.ButtonNeutralBorder,
                shape = RoundedCornerShape(JarvesDimens.ControlRadius)
            )
            .clickable(onClickLabel = category.label, onClick = onClick)
            .semantics {
                contentDescription = category.label + if (selected) ", selected" else ""
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = category.icon(),
            contentDescription = null,
            tint = if (selected) JarvesColors.Accent else JarvesColors.TextSecondary,
            modifier = Modifier.size(JarvesDimens.IconSmall)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = category.label,
                color = if (selected) JarvesColors.Accent else JarvesColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = category.description,
                color = JarvesColors.TextMuted,
                fontSize = 10.5.sp,
                lineHeight = 14.sp
            )
        }
        if (selected) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(JarvesColors.Accent)
            )
        }
    }
}
