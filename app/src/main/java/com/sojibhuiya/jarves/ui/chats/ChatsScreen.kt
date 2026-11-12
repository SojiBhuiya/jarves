package com.sojibhuiya.jarves.ui.chats

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sojibhuiya.jarves.chat.ChatMessage
import com.sojibhuiya.jarves.chat.ChatSource
import com.sojibhuiya.jarves.chat.Conversation
import com.sojibhuiya.jarves.chat.ConversationTitles
import com.sojibhuiya.jarves.ui.components.CompactActionButton
import com.sojibhuiya.jarves.ui.components.JarvesBackRow
import com.sojibhuiya.jarves.ui.components.JarvesCard
import com.sojibhuiya.jarves.ui.components.JarvesIcon
import com.sojibhuiya.jarves.ui.icons.JarvesIcons
import com.sojibhuiya.jarves.ui.theme.JarvesColors
import com.sojibhuiya.jarves.ui.theme.JarvesDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat history: a list of saved conversations, and — once one is opened — only that
 * conversation's messages, oldest first.
 */
@Composable
fun ChatsScreen(
    conversations: List<Conversation>,
    selectedConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onBack: () -> Unit
) {
    val selected = conversations.firstOrNull { it.id == selectedConversationId }
    Box(
        Modifier
            .fillMaxSize()
            .background(JarvesColors.Background)
            .navigationBarsPadding()
    ) {
        if (selected == null) {
            ConversationList(
                conversations = conversations,
                onSelectConversation = onSelectConversation,
                onNewChat = onNewChat,
                onBack = onBack
            )
        } else {
            ConversationDetail(
                conversation = selected,
                onNewChat = onNewChat,
                onBack = onBack
            )
        }
    }
}

@Composable
private fun ChatsHeader(
    title: String,
    onBack: () -> Unit,
    onNewChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = JarvesDimens.ScreenPadding, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JarvesBackRow(onBack = onBack)
        Text(
            text = title,
            color = JarvesColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        CompactActionButton(icon = JarvesIcons.SquarePen, label = "New Chat", onClick = onNewChat)
    }
}

@Composable
private fun ConversationList(
    conversations: List<Conversation>,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ChatsHeader(title = "Chats", onBack = onBack, onNewChat = onNewChat)
        if (conversations.isEmpty()) {
            EmptyChats(onNewChat = onNewChat)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = JarvesDimens.ScreenPadding,
                    end = JarvesDimens.ScreenPadding,
                    top = 4.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(JarvesDimens.ItemGap)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        onClick = { onSelectConversation(conversation.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    val subtitle = when {
        conversation.isEmpty -> "No messages yet"
        conversation.messageCount == 1 -> "1 message · ${formatTime(conversation.updatedAt)}"
        else -> "${conversation.messageCount} messages · ${formatTime(conversation.updatedAt)}"
    }
    JarvesCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = conversation.title, onClick = onClick)
                .heightIn(min = 60.dp)
                .padding(horizontal = JarvesDimens.CardPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(JarvesColors.SurfaceMuted)
                    .border(1.dp, JarvesColors.Border, RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                JarvesIcon(JarvesIcons.MessageCircle, contentDescription = null, size = JarvesDimens.IconSmall)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = conversation.title.ifBlank { ConversationTitles.FALLBACK_TITLE },
                    color = JarvesColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = JarvesColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            JarvesIcon(JarvesIcons.ChevronRight, contentDescription = null, tint = JarvesColors.IconMuted, size = 16.dp)
        }
    }
}

@Composable
private fun EmptyChats(onNewChat: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(JarvesDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        JarvesIcon(JarvesIcons.MessageCircle, contentDescription = null, tint = JarvesColors.IconMuted, size = JarvesDimens.IconLarge)
        Spacer(Modifier.height(10.dp))
        Text("No chats yet", color = JarvesColors.TextSecondary, fontSize = 13.5.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Conversations you have with Jarves appear here.",
            color = JarvesColors.TextMuted,
            fontSize = 11.5.sp
        )
        Spacer(Modifier.height(14.dp))
        CompactActionButton(icon = JarvesIcons.SquarePen, label = "New Chat", onClick = onNewChat)
    }
}

@Composable
private fun ConversationDetail(
    conversation: Conversation,
    onNewChat: () -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(conversation.id, conversation.messageCount) {
        if (conversation.messageCount > 0) {
            listState.animateScrollToItem(conversation.messageCount - 1)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ChatsHeader(
            title = conversation.title.ifBlank { ConversationTitles.FALLBACK_TITLE },
            onBack = onBack,
            onNewChat = onNewChat
        )
        if (conversation.isEmpty) {
            Column(
                Modifier.fillMaxSize().padding(JarvesDimens.ScreenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                JarvesIcon(JarvesIcons.Mic, contentDescription = null, tint = JarvesColors.IconMuted, size = JarvesDimens.IconLarge)
                Spacer(Modifier.height(10.dp))
                Text("This chat is empty", color = JarvesColors.TextSecondary, fontSize = 13.5.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Start talking from the Home screen — new turns are saved to this chat.",
                    color = JarvesColors.TextMuted,
                    fontSize = 11.5.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = JarvesDimens.ScreenPadding,
                    end = JarvesDimens.ScreenPadding,
                    top = 4.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversation.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.fromUser
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (fromUser) JarvesColors.SurfaceMuted else JarvesColors.Surface)
                .border(
                    1.dp,
                    if (fromUser) JarvesColors.Border else JarvesColors.Border,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = if (fromUser) "You" else "Jarves",
                color = if (fromUser) JarvesColors.TextMuted else JarvesColors.Accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            // Selectable: a search-failure report lands here and has to be copyable.
            SelectionContainer {
                Text(
                    text = message.text,
                    color = if (fromUser) JarvesColors.TextSecondary else JarvesColors.TextPrimary,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                )
            }
            if (!fromUser && message.sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "SOURCES",
                    color = JarvesColors.TextMuted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium
                )
                message.sources.forEach { source ->
                    SourceRow(source = source, onOpen = { runCatching { uriHandler.openUri(source.url) } })
                }
            }
        }
    }
}

/** One cited web source: host as the label, tappable to open the original page. */
@Composable
private fun SourceRow(source: ChatSource, onOpen: () -> Unit) {
    val label = source.url.substringAfter("://", source.url).substringBefore('/').removePrefix("www.")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open source $label", onClick = onOpen)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JarvesIcon(JarvesIcons.ChevronRight, contentDescription = null, tint = JarvesColors.TextMuted, size = 12.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = JarvesColors.Accent,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = TextDecoration.Underline
        )
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
