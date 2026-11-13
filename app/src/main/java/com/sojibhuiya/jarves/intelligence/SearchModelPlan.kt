package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson

/**
 * The one and only place that decides which models a REST Google Search grounding call may use.
 *
 * Order, and why this order:
 *
 * 1. `gemini-3.5-flash-lite` — verified in the official Grounding with Google Search "Supported
 *    models" table (https://ai.google.dev/gemini-api/docs/google-search → "Supported models") and
 *    in the thinking-levels table (https://ai.google.dev/gemini-api/docs/thinking → "Controlling
 *    thinking"). Free tier on the owner's key: 15 RPM / 250K TPM / 500 RPD.
 * 2. `gemini-3.6-flash` — same two tables (grounding ✔️, thinking levels minimal..high). Free tier
 *    on the owner's key: 5 RPM / 250K TPM / **20 RPD**, which is why it is last: it is the model
 *    that runs out of quota first and must never be the default.
 *
 * Deliberately NOT in the list:
 *
 * * `gemini-3.5-flash` — the official model page
 *   (https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash) states it is **deprecated** and
 *   that "API requests to gemini-3.5-flash are automatically routed to gemini-3.6-flash". Keeping
 *   it would therefore not be a separate fallback at all: it would silently spend the 20 RPD of
 *   3.6 Flash, twice. Dropped on purpose.
 * * every Gemini 2.5 model — deprecated for the search path; the 0.2.4 device log shows Google
 *   answering `models/gemini-2.5-flash-lite is no longer available to new users`.
 * * every paid-only or more expensive model — never selected automatically (see [isFreeTierModel]).
 *
 * A model can only be added here after its grounding support is verified in the official docs.
 */
object SearchModels {

    /** The ordered list. The first entry is the preferred/default model. */
    val ORDER: List<String> = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.6-flash"
    )

    /** The model used when nothing is cached yet. */
    val PREFERRED: String get() = ORDER.first()

    /** Free-tier grounding models, per the docs above. Nothing outside this set is auto-selected. */
    val FREE_TIER: Set<String> = ORDER.toSet()

    /** How long a model that worked stays "known good" before it may be probed again. */
    const val CACHE_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L

    /** Truth for [GroundedSearchClient]: may this model be used without asking the user? */
    fun isFreeTierModel(model: String): Boolean = model in FREE_TIER

    /** Only this list may ever be used; anything else is a programming error, not a fallback. */
    fun assertKnown(model: String): String {
        require(model in ORDER) { "model '$model' is not in SearchModels.ORDER" }
        return model
    }
}

/** Persistence seam for [SearchModelHealth]. Implemented with SharedPreferences on device. */
interface ModelStateStore {
    fun read(): String?

    fun write(value: String)
}

/** Test/no-op store. */
class InMemoryModelStateStore(private var value: String? = null) : ModelStateStore {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

/**
 * Remembers which search model actually worked, and which ones ran out of quota today, so a normal
 * search never has to probe the list again.
 *
 * Rules (each one unit-tested):
 * * a model that answered is cached for [ttlMillis] (24 h by default);
 * * a model that failed for a model-side reason (404 / "no longer available") loses the cache
 *   immediately, so the next search starts from the top of the list again;
 * * a model that hit HTTP 429 is skipped until the next local midnight, then allowed again;
 * * nothing here decides *which* models exist — that is [SearchModels.ORDER] only.
 */
class SearchModelHealth(
    private val store: ModelStateStore = InMemoryModelStateStore(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = SearchModels.CACHE_TTL_MILLIS
) {

    private data class State(
        val cachedModel: String? = null,
        val cachedAtMillis: Long = 0L,
        val exhaustedUntil: Map<String, Long> = emptyMap()
    )

    private fun load(): State {
        val raw = store.read() ?: return State()
        val root = runCatching { MiniJson.parse(raw) }.getOrNull() as? Map<*, *> ?: return State()
        val exhausted = (root["exhausted"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val model = k as? String ?: return@mapNotNull null
            val until = (v as? Number)?.toLong() ?: return@mapNotNull null
            model to until
        }.toMap()
        return State(
            cachedModel = (root["cached"] as? String)?.takeIf { it in SearchModels.ORDER },
            cachedAtMillis = (root["cachedAt"] as? Number)?.toLong() ?: 0L,
            exhaustedUntil = exhausted
        )
    }

    private fun save(state: State) {
        runCatching {
            store.write(
                MiniJson.encode(
                    linkedMapOf<String, Any?>(
                        "cached" to state.cachedModel,
                        "cachedAt" to state.cachedAtMillis,
                        "exhausted" to state.exhaustedUntil
                    )
                )
            )
        }
    }

    /** The cached model, or `null` when there is none or it has expired. */
    fun cachedModel(now: Long = clock()): String? {
        val state = load()
        val model = state.cachedModel ?: return null
        if (now - state.cachedAtMillis >= ttlMillis) return null
        return model
    }

    /** When the current cache entry expires (epoch millis), or 0 when nothing is cached. */
    fun cacheExpiresAtMillis(now: Long = clock()): Long {
        val state = load()
        if (state.cachedModel == null) return 0L
        if (now - state.cachedAtMillis >= ttlMillis) return 0L
        return state.cachedAtMillis + ttlMillis
    }

    /** A model answered: cache it as known-good for [ttlMillis]. */
    fun onSuccess(model: String, now: Long = clock()) {
        val state = load().let { it.copy(cachedModel = model, cachedAtMillis = now) }
        save(state)
    }

    /** A model failed for a model-side reason: stop trusting the cache immediately. */
    fun onModelUnavailable(model: String, now: Long = clock()) {
        val state = load()
        val cleared = if (state.cachedModel == model) {
            state.copy(cachedModel = null, cachedAtMillis = 0L)
        } else {
            state
        }
        save(cleared)
    }

    /** A model returned HTTP 429: skip it until the next local midnight. */
    fun onDailyQuotaExhausted(model: String, now: Long = clock()) {
        val until = nextLocalMidnight(now)
        val state = load()
        val clearedCache = if (state.cachedModel == model) {
            state.copy(cachedModel = null, cachedAtMillis = 0L)
        } else {
            state
        }
        save(clearedCache.copy(exhaustedUntil = clearedCache.exhaustedUntil + (model to until)))
    }

    /** True when [model] hit its daily limit and must not be tried again today. */
    fun isExhaustedToday(model: String, now: Long = clock()): Boolean {
        val until = load().exhaustedUntil[model] ?: return false
        return now < until
    }

    /** Models currently skipped, and the moment each becomes usable again. */
    fun exhaustedModels(now: Long = clock()): List<Pair<String, Long>> =
        load().exhaustedUntil.filterValues { now < it }.toList().sortedBy { it.second }

    /** Models in [SearchModels.ORDER] that may be tried right now, best first. */
    fun usableModels(now: Long = clock()): List<String> {
        val cached = cachedModel(now)
        val ordered = SearchModels.ORDER.sortedBy { if (it == cached) 0 else 1 }
        return ordered.filterNot { isExhaustedToday(it, now) }
    }

    /** One secret-free line for the on-device diagnostic block. */
    fun describe(now: Long = clock()): String {
        val cached = cachedModel(now)
        val leftMinutes = if (cached == null) 0L else (cacheExpiresAtMillis(now) - now) / 60_000L
        val skipped = exhaustedModels(now)
        return buildString {
            append("cached=").append(cached ?: "none")
            if (cached != null) append(" (").append(leftMinutes).append("min left)")
            append(" | skipped=")
            if (skipped.isEmpty()) {
                append("none")
            } else {
                skipped.joinTo(this, ", ") { (model, until) ->
                    val mins = ((until - now) / 60_000L).coerceAtLeast(0)
                    "$model(quota ${mins}min)"
                }
            }
        }
    }

    companion object {
        /** The instant the local day rolls over; the quota window is a day, so this is our reset. */
        fun nextLocalMidnight(now: Long): Long {
            val zone = java.util.TimeZone.getDefault()
            val calendar = java.util.Calendar.getInstance(zone)
            calendar.timeInMillis = now
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return calendar.timeInMillis
        }
    }
}
