package com.sojibhuiya.jarves

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live audio-to-audio transport.
 * Mic: 16 kHz, mono, PCM16. Speaker: 24 kHz, mono, PCM16.
 */
class GeminiLiveVoice(
    private val apiKey: String,
    private val assistantName: String,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val MODEL = "gemini-3.8-live"
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
        private const val CHUNK_BYTES = 640 // 20 ms at 16 kHz, mono, PCM16
    }

    private val running = AtomicBoolean(false)
    private val setupDone = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var captureThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private val playbackActive = AtomicBoolean(false)
    @Volatile private var playbackThread: Thread? = null

    fun start() {
        if (apiKey.isBlank()) {
            onError("Gemini API Key যোগ করুন")
            return
        }
        if (!running.compareAndSet(false, true)) return
        onStatus("Gemini Live-এ সংযোগ হচ্ছে…")

        val request = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject()
                    .put("setup", JSONObject()
                        .put("model", "models/$MODEL")
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                            .put("speechConfig", JSONObject()
                                .put("voiceConfig", JSONObject()
                                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Aoede")))))
                        .put("systemInstruction", JSONObject()
                            .put("parts", org.json.JSONArray().put(JSONObject().put(
                                "text",
                                "You are $assistantName, a warm and capable personal voice assistant. " +
                                    "Speak naturally and conversationally. Understand and respond in Bengali, " +
                                    "English, Banglish, or the language the user uses. Prefer natural Bangla " +
                                    "when the user speaks Bangla. Keep responses clear and useful. " +
                                    "This is a live voice conversation. Do not say you are reading a transcript."
                            ))))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject()))
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    if (root.has("setupComplete")) {
                        setupDone.set(true)
                        startAudioPlayback()
                        startAudioCapture()
                        onStatus("সংযুক্ত। এখন কথা বলুন")
                    }

                    val serverContent = root.optJSONObject("serverContent")
                    if (serverContent != null) {
                        if (serverContent.optBoolean("interrupted", false)) {
                            playbackQueue.clear()
                            try { player?.pause(); player?.flush(); player?.play() } catch (_: Exception) {}
                            onStatus("আপনার কথা শুনছি…")
                        }

                        serverContent.optJSONObject("inputTranscription")?.optString("text")
                            ?.takeIf { it.isNotBlank() }?.let { onTranscript(it, false) }

                        serverContent.optJSONObject("outputTranscription")?.optString("text")
                            ?.takeIf { it.isNotBlank() }?.let { onTranscript(it, true) }

                        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                                val encoded = inline?.optString("data").orEmpty()
                                if (encoded.isNotBlank()) playbackQueue.offer(Base64.decode(encoded, Base64.DEFAULT))
                            }
                        }
                        if (serverContent.optBoolean("turnComplete", false)) {
                            onStatus("শুনছি… আপনি কথা বলতে পারেন")
                        }
                    }

                    if (root.has("goAway")) onStatus("সংযোগ পুনরায় চালু করতে হবে")
                } catch (e: Exception) {
                    onError("Live response পড়তে সমস্যা: ${e.message ?: "unknown"}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopAudio()
                running.set(false)
                onError("Gemini Live সংযোগ ব্যর্থ: ${t.message ?: "network error"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopAudio()
                running.set(false)
                onStatus("Live session বন্ধ হয়েছে")
            }
        })
    }

    private fun startAudioCapture() {
        if (!running.get() || !setupDone.get()) return
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("Microphone audio format unsupported")

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, CHUNK_BYTES * 4)
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                throw IllegalStateException("Microphone could not start")
            }
            recorder = audioRecord
            audioRecord.startRecording()

            captureThread = Thread({
                val buffer = ByteArray(CHUNK_BYTES)
                while (running.get()) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count > 0 && running.get()) {
                        val payload = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                        val message = JSONObject().put(
                            "realtimeInput",
                            JSONObject().put("audio", JSONObject()
                                .put("mimeType", "audio/pcm;rate=16000")
                                .put("data", payload))
                        )
                        socket?.send(message.toString())
                    }
                }
            }, "gemini-live-mic").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            onError("Microphone error: ${e.message ?: "unknown"}")
            stop()
        }
    }

    // Blocking AudioTrack writes must stay off OkHttp's WebSocket reader thread.
    private fun startAudioPlayback() {
        if (!playbackActive.compareAndSet(false, true)) return
        playbackThread = Thread({
            try {
                while (running.get() && playbackActive.get()) {
                    val pcm = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    try {
                        var track = player
                        if (track == null) {
                            val minBuffer = AudioTrack.getMinBufferSize(
                                OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                            )
                            track = AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(OUTPUT_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                .setBufferSizeInBytes(maxOf(minBuffer * 2, OUTPUT_RATE))
                                .setTransferMode(AudioTrack.MODE_STREAM).build()
                            player = track
                            track.play()
                        }
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    } catch (e: Exception) {
                        onError("Audio playback error: ${e.message ?: "unknown"}")
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown.
            }
        }, "gemini-live-playback").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
            socket?.close(1000, "User stopped session")
        } catch (_: Exception) {}
        stopAudio()
        onStatus("Live voice বন্ধ")
    }

    private fun stopAudio() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        playbackActive.set(false)
        playbackQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
        try { player?.pause(); player?.flush(); player?.release() } catch (_: Exception) {}
        player = null
        setupDone.set(false)
    }
}
