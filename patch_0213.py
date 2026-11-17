#!/usr/bin/env python3
"""0.2.13 patch: applies every anchored edit to the files that already existed at 0.2.12.

Restore path (the workspace snapshot has wiped work0212 more than once):
  1. git clone -b jarves-wip-backup https://github.com/SojiBhuiya/jarves.git work0212
  2. put the four new files back (already in git once committed):
     app/src/main/java/.../intelligence/ServerBusyRetry.kt
     app/src/main/java/.../intelligence/LivePingSetting.kt
     app/src/main/java/.../storage/AndroidLivePingStore.kt
     app/src/test/java/.../intelligence/ServerBusyRetryTest.kt
  3. python3 patch_0213.py   (idempotent: refuses to apply an edit twice)
Every edit is anchored on ASCII text, never on Bengali, and every anchor must match exactly once.
"""
import sys

ROOT = "/home/user/work0212/app/src/main/java/com/sojibhuiya/jarves"
TEST = "/home/user/work0212/app/src/test/java/com/sojibhuiya/jarves"
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


# ======================================================================= NetworkReconnect ==
NR = ROOT + "/intelligence/NetworkReconnect.kt"

edit(NR, r'''    /** Nothing to do (the user stopped, or the session is not running). */
    data object None : ReconnectAction''',
     r'''    /**
     * 0.2.13: the server refused the new connection with `1008 "…already connected to an existing
     * client"`. Nothing is wrong with the network, so this has its own schedule and does **not**
     * spend one of the [maxAttempts] normal attempts.
     */
    data class RetryServerBusy(val attempt: Int, val delayMillis: Long) : ReconnectAction

    /** Nothing to do (the user stopped, or the session is not running). */
    data object None : ReconnectAction''', "nr.action")

edit(NR, r'''    /** The network never came back within the deadline. */
    NO_NETWORK_TIMEOUT
}''',
     r'''    /** The network never came back within the deadline. */
    NO_NETWORK_TIMEOUT,

    /**
     * 0.2.13: the server kept answering `1008 "already connected to an existing client"` for the
     * whole server-busy budget (~30 s), so the session is ended cleanly instead of waiting forever.
     */
    SERVER_BUSY_TIMEOUT
}''', "nr.reason")

edit(NR, r'''    private val events = mutableListOf<String>()
''',
     r'''    private val events = mutableListOf<String>()

    /**
     * 0.2.13 (task 4): every timeline entry is printed as `t+12.8s` relative to this moment, never
     * as an epoch value. Set by [startTimeline] when a session starts.
     */
    private var sessionStartMillis: Long = 0L

    /** 0.2.13: the 1008 "already connected" schedule (3 s, 5 s, 8 s, 12 s, ~30 s in total). */
    val serverBusy: ServerBusyRetry = ServerBusyRetry()
''', "nr.fields")

# startTimeline must be inserted right after timelineText
edit(NR, r'''    fun timelineText(): String =
        if (events.isEmpty()) "none" else events.joinToString(" -> ")''',
     r'''    fun timelineText(): String =
        if (events.isEmpty()) "none" else events.joinToString(" -> ")

    /** 0.2.13 (task 4): timestamps below are relative to this instant. */
    fun startTimeline(now: Long) {
        sessionStartMillis = now
        events.clear()
    }

    /** `t+12.8s` for the current call. */
    private fun stamp(now: Long): String = formatRelativeMillis(now, sessionStartMillis)''', "nr.startTimeline")

edit(NR, r'''        lastAttemptAtMillis = 0L
        events.clear()
    }''',
     r'''        lastAttemptAtMillis = 0L
        serverBusy.reset()
        events.clear()
    }''', "nr.reset")

edit(NR, r'''    /** The reconnect worked: the session continues. */
    fun onAttemptSucceeded(now: Long) {
        attemptsUsed = 0
        waitingForNetwork = false
        events += "${event(now)} reconnected"
    }''',
     r'''    /** The reconnect worked: the session continues. */
    fun onAttemptSucceeded(now: Long) {
        attemptsUsed = 0
        waitingForNetwork = false
        // The session runs again: a later 1008 episode starts its schedule from 3 s again.
        if (serverBusy.active) events += "${event(now)} server-busy cleared (${serverBusy.describe(now)})"
        serverBusy.reset()
        events += "${event(now)} reconnected"
    }

    /**
     * 0.2.13: the server refused the new socket with `1008 "…already connected to an existing
     * client"`. Returns [ReconnectAction.RetryServerBusy] on its own schedule (3 s, 5 s, 8 s, 12 s)
     * or [ReconnectAction.GiveUp] once the ~30 s budget is spent. [attemptsUsed] is untouched.
     */
    fun onServerBusy(now: Long): ReconnectAction {
        if (gaveUp) return ReconnectAction.None
        val delay = serverBusy.nextDelayMillis(now)
        if (delay == null) {
            events += "${event(now)} server still busy after ${serverBusy.describe(now)}"
            return giveUp(now, GiveUpReason.SERVER_BUSY_TIMEOUT)
        }
        events += "${event(now)} server busy -> retry in ${delay}ms (${serverBusy.describe(now)})"
        return ReconnectAction.RetryServerBusy(attempt = serverBusy.attemptsUsed, delayMillis = delay)
    }''', "nr.onServerBusy")

edit(NR, r'''    private fun event(now: Long): String = "${now / 1000.0}s"''',
     r'''    /** 0.2.13 (task 4): `t+12.8s` instead of an epoch value like `1.7E7s`. */
    private fun event(now: Long): String = stamp(now)''', "nr.event")

edit(NR, r'''            GiveUpReason.ATTEMPTS_EXHAUSTED ->
                "ইন্টারনেট ঠিক আছে, কিন্তু ৩ বার চেষ্টা করেও সংযোগ হয়নি। একটু পরে আবার শুরু করুন।"''',
     r'''            GiveUpReason.ATTEMPTS_EXHAUSTED ->
                "ইন্টারনেট ঠিক আছে, কিন্তু ৩ বার চেষ্টা করেও সংযোগ হয়নি। একটু পরে আবার শুরু করুন।"
            GiveUpReason.SERVER_BUSY_TIMEOUT ->
                "সার্ভার এখনো পুরোনো সংযোগটা ছাড়েনি, তাই প্রায় ৩০ সেকেন্ড চেষ্টার পর বন্ধ করছি। " +
                    "আবার শুরু করুন — সাধারণত সাথে সাথে যুক্ত হয়ে যায়।"''', "nr.message")

# ========================================================================== ReconnectPolicy ==
edit(ROOT + "/intelligence/ReconnectPolicy.kt",
     r'''    AUDIO_READ_FAILED''',
     r'''    AUDIO_READ_FAILED,

    /**
     * 0.2.13: the server refused the new socket with `1008 "…already connected to an existing
     * client"`. Transient by nature (the old connection has to be torn down by the server), so it
     * has its own schedule and does not spend a normal attempt.
     */
    SERVER_BUSY''', "rp.cause")

# ============================================================================ build.gradle ==
edit("/home/user/work0212/app/build.gradle.kts", 'versionCode = 14', 'versionCode = 15', "versionCode")
edit("/home/user/work0212/app/build.gradle.kts", 'versionName = "0.2.12"', 'versionName = "0.2.13"', "versionName")

# ==================================================================== AudioSendReconnectTest ==
edit(TEST + "/intelligence/AudioSendReconnectTest.kt",
     r'''            is ReconnectAction.Attempt -> "attempt"
            is ReconnectAction.GiveUp -> "giveup"''',
     r'''            is ReconnectAction.Attempt -> "attempt"
            // 0.2.13: an audio-send failure is never a server-busy refusal, so this branch only
            // exists to keep the `when` exhaustive. The dedicated 1008 test lives in
            // ServerBusyRetryTest.
            is ReconnectAction.RetryServerBusy -> "server-busy"
            is ReconnectAction.GiveUp -> "giveup"''', "test.exhaustive")

if __name__ == "__main__":
    sys.exit(1 if apply() else 0)
