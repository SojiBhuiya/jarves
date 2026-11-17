#!/usr/bin/env python3
"""0.2.13 patch, part 2: the live engine (GeminiLiveVoice.kt) and the settings screen (MainActivity.kt).

Same rules as part 1: ASCII anchors, exactly one match each, and a re-run skips edits that are
already in place. The engine file is not JVM-compilable here (Android + OkHttp types), so this script
is the restore path if the workspace is wiped again.
"""
import sys

ROOT = "/home/user/work0212/app/src/main/java/com/sojibhuiya/jarves"
GL = ROOT + "/GeminiLiveVoice.kt"
MA = ROOT + "/MainActivity.kt"
EDITS = []


def edit(path, old, new, label):
    EDITS.append((path, old, new, label))


def apply():
    failures = 0
    for path, old, new, label in EDITS:
        with open(path, encoding="utf-8") as f:
            text = f.read()
        if new and new in text and old not in text:
            print("SKIP (already applied):", label)
            continue
        count = text.count(old)
        if count != 1:
            print("FAIL count=%d: %s" % (count, label))
            failures += 1
            continue
        with open(path, "w", encoding="utf-8") as f:
            f.write(text.replace(old, new))
        print("ok:", label)
    return failures


# ------------------------------------------------------------------------------- imports ----
edit(GL, 'import com.sojibhuiya.jarves.intelligence.LiveFailureRouter\n',
     'import com.sojibhuiya.jarves.intelligence.CloseAlreadyConnected\n'
     'import com.sojibhuiya.jarves.intelligence.InterruptionLog\n'
     'import com.sojibhuiya.jarves.intelligence.LiveFailureRouter\n'
     'import com.sojibhuiya.jarves.intelligence.LivePingSetting\n'
     'import com.sojibhuiya.jarves.intelligence.ServerBusyRetry\n'
     'import com.sojibhuiya.jarves.intelligence.formatSeconds\n', "gl.imports")

edit(GL, 'const val FIX_TAG = "0.2.12-a2a3a4-b1d1d2b7"',
     'const val FIX_TAG = "0.2.13-reconnect-1008"', "gl.fixTag")

# ------------------------------------------------------------- constructor: ping experiment --
edit(GL, r'''    systemDiagnostics: () -> String = { "unknown (no Android context)" }
) {''',
     r'''    systemDiagnostics: () -> String = { "unknown (no Android context)" },
    /**
     * 0.2.13 (task 3): EXPERIMENT, off by default (0). When set to 15 or 20, OkHttp sends a
     * WebSocket ping every that many seconds, which is the documented way to notice a half-open
     * connection (OkHttp `pingInterval`). The Live docs say nothing about ping frames, RFC 6455
     * §5.5.2 says a peer MUST answer a ping with a pong, and the real Gemini behaviour is NOT-RUN,
     * so this stays behind a hidden setting.
     */
    private val pingIntervalSeconds: Int = 0
) {''', "gl.pingParam")

edit(GL, r'''    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()''',
     r'''    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 0.2.13 (task 3): only when the hidden experiment is switched on. `pingInterval` makes
        // OkHttp fail the socket when the peer stops answering pings, which is exactly the
        // half-open case that produced AUDIO_SEND_FAILED on a connection the server still had open.
        .apply { if (pingIntervalSeconds > 0) pingInterval(pingIntervalSeconds.toLong(), TimeUnit.SECONDS) }
        .build()''', "gl.pingClient")

# ------------------------------------------------------------------------- evidence fields ----
edit(GL, r'''    /** The last frame that was skipped because it could not be parsed (diagnostics only). */
    @Volatile private var lastSkippedFrame = "none"''',
     r'''    /** The last frame that was skipped because it could not be parsed (diagnostics only). */
    @Volatile private var lastSkippedFrame = "none"

    // ---- 0.2.13 (tasks 3 and 4): evidence for a send failure, and interruption timing ----
    /** When the last frame arrived from the server — "how long has this socket been silent?". */
    @Volatile private var lastServerMessageAtMillis: Long = 0L
    /** When `send()` last returned true. */
    @Volatile private var lastSendOkAtMillis: Long = 0L
    /** Number of `send()` calls that returned false (or hit the backlog limit) on this session. */
    @Volatile private var sendFailureCount: Int = 0
    /** One line per send failure: queue size, silence, last good send, network, foreground. */
    @Volatile private var lastSendFailureEvidence: String = "none"
    /** Network type at the previous send, so a change can be shown. */
    @Volatile private var lastSendNetworkType: String = "unknown"
    /** 0.2.13 (task 4): how many interruptions, and how long each lasted. */
    private val interruptions = InterruptionLog()''', "gl.fields")

# --------------------------------------------------------------------- constant for backlog ----
edit(GL, r'''        /** Auto mode: one search per user question, as the 0.2.7 spec requires. */
        const val MAX_SEARCHES_PER_QUESTION = 1''',
     r'''        /** Auto mode: one search per user question, as the 0.2.7 spec requires. */
        const val MAX_SEARCHES_PER_QUESTION = 1

        /**
         * 0.2.13 (task 2): "do not queue more than a few seconds of audio". Input is 16 kHz mono
         * PCM16 = 32 000 bytes/s, so 64 000 bytes is two seconds. Above this the queue is treated as
         * backpressure and the socket is reconnected instead of buffering more.
         */
        const val MAX_SEND_QUEUE_BYTES = 64_000L''', "gl.const")

# ---------------------------------------------------------------- evidence + server-busy path --
edit(GL, r'''    private fun tryAutomaticReconnect(cause: ReconnectCause, code: Int, reason: String): Boolean {''',
     r'''    /**
     * 0.2.13 (task 3): one line of measured evidence for a failed (or alarming) audio send.
     *
     * Nothing here is guessed: every value comes from OkHttp or from the system reader. It answers
     * "why did `send()` fail on a socket the server still saw as connected?" — a half-open
     * connection shows up as a large silence, a stale last-send time and often a network change.
     */
    private fun evidence(socketSendOk: Boolean, backlogBytes: Long, reason: String): String {
        val now = System.currentTimeMillis()
        val silentFor = if (lastServerMessageAtMillis <= 0L) -1L else now - lastServerMessageAtMillis
        val sinceGoodSend = if (lastSendOkAtMillis <= 0L) -1L else now - lastSendOkAtMillis
        val network = runCatching { netMonitor.snapshot() }.getOrDefault(NetworkSnapshot.UNKNOWN)
        val networkChanged = network.type != lastSendNetworkType
        val env = runCatching { envProvider.snapshot() }.getOrDefault(SessionEnvironment.UNKNOWN)
        val foreground = when (env.appVisible) {
            true -> "foreground"
            false -> "background"
            null -> "unknown"
        }
        val screen = when (env.screenInteractive) {
            true -> "screen on"
            false -> "screen off"
            null -> "screen unknown"
        }
        lastSendNetworkType = network.type
        return "send=" + (if (socketSendOk) "ok" else "false") +
            ", queueSize=" + (if (backlogBytes < 0L) "unknown" else "${backlogBytes}B") +
            ", sinceLastServerMessage=" + (if (silentFor < 0L) "none yet" else humanMillis(silentFor)) +
            ", sinceLastGoodSend=" + (if (sinceGoodSend < 0L) "none yet" else humanMillis(sinceGoodSend)) +
            ", network=${network.type}" + (if (networkChanged) " (CHANGED)" else "") +
            ", app=$foreground ($screen)" +
            ", reason=$reason"
    }

    /** `12.8s` — used inside the evidence line. */
    private fun humanMillis(millis: Long): String = formatSeconds(millis) + "s"

    /**
     * 0.2.13 (task 2): `1008 "…already connected to an existing client"` is transient. It does not
     * touch the three normal attempts; it waits 3 s, 5 s, 8 s, 12 s (about 30 s in total) and then
     * ends the session cleanly. The status stays calm and the microphone keeps running, because the
     * user is still talking — only the audio frames are dropped while the socket handshakes.
     *
     * @return true when the condition was recognised and handled (the caller must not also report a
     *   failure); false when this is an ordinary close and the normal path applies.
     */
    private fun tryServerBusyRetry(code: Int, reason: String): Boolean {
        if (!CloseAlreadyConnected.matches(code, reason)) return false
        if (!running.get() || closeInitiator.intentional) return false
        if (terminalErrorReported.get()) return false
        interruptions.begin(System.currentTimeMillis(), CloseAlreadyConnected.describe(code, reason))
        val action = reconnectPlan.onServerBusy(System.currentTimeMillis())
        lastReconnectNote = "server busy (${CloseAlreadyConnected.describe(code, reason)}) -> " +
            when (action) {
                is ReconnectAction.RetryServerBusy -> "retry #${action.attempt} in ${action.delayMillis}ms"
                is ReconnectAction.GiveUp -> "gave up"
                else -> action.toString()
            }
        return when (action) {
            is ReconnectAction.RetryServerBusy -> {
                scheduleReconnect(
                    ReconnectCause.SERVER_BUSY,
                    code,
                    reason,
                    action.delayMillis,
                    attempt = 0,
                    statusOverride = ServerBusyRetry.STATUS_FIXING_CONNECTION
                )
                true
            }
            is ReconnectAction.GiveUp -> {
                giveUpAfterReconnects(code, reason, action.message)
                true
            }
            else -> false
        }
    }

    private fun tryAutomaticReconnect(cause: ReconnectCause, code: Int, reason: String): Boolean {''',
     "gl.evidence+serverBusy")

# --------------------------------------------------------------- capture loop: socket safety --
edit(GL, r'''                        val activeSocket = socket
                        // 0.2.9: during a reconnect the socket is null or still handshaking on
                        // purpose. Dropping those frames is correct; failing the session is not.
                        if (activeSocket == null || !setupDone.get()) {
                            if (running.get()) continue else break
                        }
                        if (!activeSocket!!.send(message.toString())) {''',
     r'''                        val activeSocket = socket
                        // 0.2.9: during a reconnect the socket is null or still handshaking on
                        // purpose. Dropping those frames is correct; failing the session is not.
                        if (activeSocket == null || !setupDone.get()) {
                            if (running.get()) continue else break
                        }
                        // 0.2.13 (task 1): `socket` may have been swapped between the read above and
                        // this line. Sending the chunk to the *previous* connection would be audio on
                        // a socket we just cancelled, so the chunk is dropped instead.
                        if (activeSocket !== socket || !running.get()) continue
                        // 0.2.13 (task 2): never let a stalled connection accumulate audio. The
                        // documented way to see the backlog is queueSize() ("Use queueSize to monitor
                        // backpressure before sending"); above a couple of seconds of PCM the
                        // reconnect is started instead of piling more frames into the queue.
                        val backlog = runCatching { activeSocket.queueSize() }.getOrDefault(0L)
                        if (backlog > MAX_SEND_QUEUE_BYTES) {
                            if (reconnectScheduled || !running.get()) continue
                            lastSendFailureEvidence = evidence(
                                socketSendOk = true, backlogBytes = backlog, reason = "queue too large"
                            )
                            sendFailureCount += 1
                            val handledBacklog = tryAutomaticReconnect(
                                ReconnectCause.AUDIO_SEND_FAILED, 0, "send queue backlog ${backlog}B"
                            )
                            if (handledBacklog) continue
                            fail("অডিও পাঠানোর queue বেড়েই চলছে (${backlog}B)। সংযোগ বন্ধ করছি।")
                            break
                        }
                        if (!activeSocket.send(message.toString())) {
                            // 0.2.13 (task 3): record *why* a send failed while the server still
                            // thought the connection was alive. Everything below is measured, never
                            // guessed: the send return value, OkHttp's own queue size, how long the
                            // socket has been silent, when the last send worked, the network type and
                            // whether it changed, and whether the app was in the foreground.
                            sendFailureCount += 1
                            lastSendFailureEvidence = evidence(
                                socketSendOk = false,
                                backlogBytes = runCatching { activeSocket.queueSize() }.getOrDefault(-1L),
                                reason = "send() returned false"
                            )''', "gl.capture")

edit(GL, r'''                        val chunks = audioChunksSent.incrementAndGet()''',
     r'''                        lastSendOkAtMillis = System.currentTimeMillis()
                        val chunks = audioChunksSent.incrementAndGet()''', "gl.sendOk")

edit(GL, r'''            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                try {''',
     r'''            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                // 0.2.13 (task 3): "how long has this socket been silent?" — the cheapest half-open
                // detector available without an experiment.
                lastServerMessageAtMillis = System.currentTimeMillis()
                try {''', "gl.serverMsg")

edit(GL, r'''                setupMessageSent = true
                setupDeclaredTool = when (searchToolMode) {''',
     r'''                setupMessageSent = true
                lastSendOkAtMillis = System.currentTimeMillis()
                setupDeclaredTool = when (searchToolMode) {''', "gl.setupSend")

# ------------------------------------------------------- onClosing / onClosed: 1008 first ------
edit(GL, r'''            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                closeCallback = "onClosing"
                setupTimeout?.cancel(false)''',
     r'''            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                closeCallback = "onClosing"
                setupTimeout?.cancel(false)
                // 0.2.13 (task 2): `1008 … already connected to an existing client` is transient —
                // the server has not let go of the previous connection yet. It gets its own calm,
                // slower schedule instead of the network retry budget.
                if (tryServerBusyRetry(code, reason)) return''', "gl.onClosing")

edit(GL, r'''            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                closeCallback = "onClosed"
                setupTimeout?.cancel(false)''',
     r'''            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                closeCallback = "onClosed"
                setupTimeout?.cancel(false)
                // 0.2.13 (task 2): same transient 1008 handling as onClosing (the one-time guard in
                // the close flow makes the second call a no-op).
                if (tryServerBusyRetry(code, reason)) return''', "gl.onClosed")

# ---------------------------------------------------------------------------- start() --------
edit(GL, r'''        if (!running.compareAndSet(false, true)) return
        failureRouter.onSessionStarted()
        terminalErrorReported.set(false)''',
     r'''        if (!running.compareAndSet(false, true)) return
        failureRouter.onSessionStarted()
        // 0.2.13 (task 4): the diagnostics timeline is relative to this instant, and the
        // interruption counter starts from zero for every session.
        val now = System.currentTimeMillis()
        interruptions.onSessionStarted(now)
        reconnectPlan.startTimeline(now)
        lastServerMessageAtMillis = 0L
        lastSendOkAtMillis = 0L
        sendFailureCount = 0
        lastSendFailureEvidence = "none"
        lastSendNetworkType = "unknown"
        terminalErrorReported.set(false)''', "gl.start")

# ------------------------------------------------------- interruption begin / end records -----
edit(GL, r'''        if (reconnectScheduled) return true

        val now = System.currentTimeMillis()
        lastReconnectCause = cause''',
     r'''        if (reconnectScheduled) return true

        val now = System.currentTimeMillis()
        // 0.2.13 (task 4): the clock starts when the socket stops working and stops on setupComplete.
        interruptions.begin(now, cause.name)
        lastReconnectCause = cause''', "gl.interruptionBegin")

edit(GL, r'''                        failureRouter.onReconnectSucceeded()
                        startAudioPlayback()''',
     r'''                        failureRouter.onReconnectSucceeded()
                        // 0.2.13 (task 4): the session runs again — close the interruption record.
                        interruptions.end(System.currentTimeMillis())
                        startAudioPlayback()''', "gl.interruptionEnd")

# ------------------------------------------------------------------ scheduleReconnect --------
edit(GL, r'''    private fun scheduleReconnect(
        cause: ReconnectCause,
        code: Int,
        reason: String,
        delay: Long,
        attempt: Int
    ) {''',
     r'''    private fun scheduleReconnect(
        cause: ReconnectCause,
        code: Int,
        reason: String,
        delay: Long,
        attempt: Int,
        /** 0.2.13: a caller-supplied calm line (used by the server-busy path). */
        statusOverride: String? = null
    ) {''', "gl.scheduleSig")

edit(GL, r'''        // One short, non-alarming line. No error bubble, no "session ended".
        onStatus(
            if (attempt > 0) "সংযোগ আবার জোড়া লাগছে… ($attempt/${ReconnectCoordinator.MAX_ATTEMPTS_DISPLAY})"
            else "নতুন নেটওয়ার্ক — আবার যুক্ত হচ্ছি…"
        )''',
     r'''        // One short, non-alarming line. No error bubble, no "session ended".
        onStatus(
            statusOverride ?: if (attempt > 0) {
                "সংযোগ আবার জোড়া লাগছে… ($attempt/${ReconnectCoordinator.MAX_ATTEMPTS_DISPLAY})"
            } else {
                "নতুন নেটওয়ার্ক — আবার যুক্ত হচ্ছি…"
            }
        )''', "gl.scheduleStatus")

# --------------------------------------------------- openSocketForReconnect: cancel the old ----
edit(GL, r'''        val old = socket
        socket = null
        runCatching {
            closeInitiator = CloseInitiator.RECONNECT
            old?.close(1000, "reconnecting")
        }
        closeInitiator = CloseInitiator.NONE''',
     r'''        val old = socket
        // Detach first: from this instant the capture loop and every callback see `socket == null`
        // (or the new socket), so no audio and no toolResponse can reach the old connection.
        socket = null
        // 0.2.13 (task 1, the likely cause of the 1008 "already connected" loop): `close()` only
        // *starts* a graceful shutdown and waits for the peer's close frame. On a half-open
        // connection that answer never comes, so the old TCP connection — and with it the server's
        // session attachment — outlived us, and the resume handshake on the new socket was refused.
        // OkHttp documents the alternative: "Immediately and violently release resources held by
        // this web socket, discarding any enqueued messages" (`WebSocket.cancel()`). So: a
        // best-effort polite close first (the server sees an orderly 1000 and can clean up), then
        // cancel() to guarantee the reader/writer and the TCP connection are gone before the new
        // socket is opened.
        runCatching {
            closeInitiator = CloseInitiator.RECONNECT
            old?.close(1000, "reconnecting")
        }
        runCatching { old?.cancel() }
        closeInitiator = CloseInitiator.NONE''', "gl.cancelOld")

# --------------------------------------------------------------- when() exhaustiveness -------
edit(GL, r'''            is ReconnectAction.Attempt -> {
                reconnectAttempts = reconnectPlan.attemptsUsed
                scheduleReconnect(cause, code, reason, action.delayMillis, action.attempt)
                true
            }

            is ReconnectAction.GiveUp -> {
                giveUpAfterReconnects(code, reason, action.message)
                true
            }

            ReconnectAction.None -> false''',
     r'''            is ReconnectAction.Attempt -> {
                reconnectAttempts = reconnectPlan.attemptsUsed
                scheduleReconnect(cause, code, reason, action.delayMillis, action.attempt)
                true
            }

            // 0.2.13: the busy schedule is produced by tryServerBusyRetry(), which schedules its own
            // reconnect. This branch only keeps the `when` exhaustive; reaching it here would mean
            // the policy returned a busy retry for a normal failure, handled the same way.
            is ReconnectAction.RetryServerBusy -> {
                scheduleReconnect(
                    cause,
                    code,
                    reason,
                    action.delayMillis,
                    attempt = 0,
                    statusOverride = ServerBusyRetry.STATUS_FIXING_CONNECTION
                )
                true
            }

            is ReconnectAction.GiveUp -> {
                giveUpAfterReconnects(code, reason, action.message)
                true
            }

            ReconnectAction.None -> false''', "gl.when")

# ------------------------------------------------------------------------ diagnostics ---------
edit(GL, r'''            "Skipped frames: ${failureRouter.skippedFrames} (last: $lastSkippedFrame) | " +
            failureRouter.describe() + " | " +''',
     r'''            "Skipped frames: ${failureRouter.skippedFrames} (last: $lastSkippedFrame) | " +
            failureRouter.describe() + " | " +
            "Send failures: $sendFailureCount (last: $lastSendFailureEvidence) | " +
            "Ping: ${LivePingSetting.describe(pingIntervalSeconds)} | " +
            "Server busy: ${reconnectPlan.serverBusy.describe(System.currentTimeMillis())} | " +''',
     "gl.diagEvidence")

edit(GL, r'''        "\nReconnect timeline: " + reconnectPlan.timelineText()''',
     r'''        "\nReconnect timeline: " + reconnectPlan.timelineText() +
        "\n" + interruptions.line(System.currentTimeMillis())''', "gl.diagTimeline")

# ================================================================== MainActivity =============
edit(MA, 'import com.sojibhuiya.jarves.storage.AndroidMicSensitivityStore\n',
     'import com.sojibhuiya.jarves.storage.AndroidMicSensitivityStore\n'
     'import com.sojibhuiya.jarves.storage.AndroidLivePingStore\n'
     'import com.sojibhuiya.jarves.intelligence.LivePingSetting\n'
     'import com.sojibhuiya.jarves.intelligence.LivePingSettings\n', "ma.imports")

edit(MA, r'''    val micSettings = remember { MicSensitivitySettings(AndroidMicSensitivityStore(context.applicationContext)) }
    var micSensitivity by remember { mutableStateOf(micSettings.current()) }''',
     r'''    val micSettings = remember { MicSensitivitySettings(AndroidMicSensitivityStore(context.applicationContext)) }
    var micSensitivity by remember { mutableStateOf(micSettings.current()) }
    // 0.2.13 (task 3): the hidden WebSocket-ping experiment. Off unless the user turns it on in
    // Settings → Advanced; nothing else in the app reads it.
    val pingSettings = remember { LivePingSettings(AndroidLivePingStore(context.applicationContext)) }
    var pingSeconds by remember { mutableStateOf(pingSettings.current()) }''', "ma.state")

edit(MA, r'''                systemDiagnostics = {
                    SystemChecks.line(context.applicationContext, LiveSessionService.isRunning())
                },''',
     r'''                systemDiagnostics = {
                    SystemChecks.line(context.applicationContext, LiveSessionService.isRunning())
                },
                // 0.2.13 (task 3): off by default; only the hidden Advanced row changes it.
                pingIntervalSeconds = pingSeconds,''', "ma.engineParam")

edit(MA, r'''                            onSelectMicSensitivity = { chosen ->
                                micSensitivity = chosen
                                micSettings.save(chosen)
                            },''',
     r'''                            onSelectMicSensitivity = { chosen ->
                                micSensitivity = chosen
                                micSettings.save(chosen)
                            },
                            pingSeconds = pingSeconds,
                            onSelectPingSeconds = { chosen ->
                                pingSeconds = chosen
                                pingSettings.save(chosen)
                            },''', "ma.callSite")

edit(MA, r'''    micSensitivity: MicSensitivity = MicSensitivity.DEFAULT,
    onSelectMicSensitivity: (MicSensitivity) -> Unit = {},''',
     r'''    micSensitivity: MicSensitivity = MicSensitivity.DEFAULT,
    onSelectMicSensitivity: (MicSensitivity) -> Unit = {},
    // 0.2.13 (task 3): the hidden ping experiment, 0 = off.
    pingSeconds: Int = LivePingSetting.DEFAULT_SECONDS,
    onSelectPingSeconds: (Int) -> Unit = {},''', "ma.signature")

edit(MA, r'''                    Spacer(Modifier.height(8.dp))
                    JarvesTextAction(
                        text = "Copy diagnostics",''',
     r'''                    Spacer(Modifier.height(8.dp))
                    // 0.2.13 (task 3): HIDDEN EXPERIMENT. Off by default; it only changes how OkHttp
                    // watches the socket for a half-open connection. Real Gemini behaviour with ping
                    // frames is NOT-RUN until it is measured on the phone.
                    SectionLabel("Ping experiment (half-open test)")
                    Spacer(Modifier.height(4.dp))
                    LivePingSetting.OPTIONS.forEach { seconds ->
                        MicSensitivityRow(
                            label = LivePingSetting.label(seconds),
                            selected = seconds == pingSeconds,
                            onClickLabel = "Ping ${LivePingSetting.label(seconds)}",
                            onSelect = { onSelectPingSeconds(seconds) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    JarvesTextAction(
                        text = "Copy diagnostics",''', "ma.uiRow")

edit(MA, r'''private fun MicSensitivityRow(
    option: MicSensitivity,
    selected: Boolean,
    onSelect: () -> Unit
) {''',
     r'''private fun MicSensitivityRow(
    option: MicSensitivity,
    selected: Boolean,
    onSelect: () -> Unit
) = MicSensitivityRow(
    label = option.label,
    selected = selected,
    onClickLabel = "Select ${option.label}",
    onSelect = onSelect,
    detail = option.detail
)

/** Same radio row, driven by a plain label so 0.2.13 can reuse it for the ping experiment. */
@Composable
private fun MicSensitivityRow(
    label: String,
    selected: Boolean,
    onClickLabel: String,
    onSelect: () -> Unit,
    detail: String? = null
) {''', "ma.rowOverload")

edit(MA, r'''            .clickable(onClickLabel = "Select ${option.label}", onClick = onSelect)''',
     r'''            .clickable(onClickLabel = onClickLabel, onClick = onSelect)''', "ma.clickLabel")

edit(MA, r'''            Text(
                text = option.label,
                color = JarvesColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = option.detail,
                color = JarvesColors.TextMuted,
                fontSize = 10.5.sp,
                lineHeight = 14.sp
            )''',
     r'''            Text(
                text = label,
                color = JarvesColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (detail != null) {
                Text(
                    text = detail,
                    color = JarvesColors.TextMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }''', "ma.rowText")

if __name__ == "__main__":
    sys.exit(1 if apply() else 0)
