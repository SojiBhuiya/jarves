package com.sojibhuiya.jarves.intelligence

/**
 * "One search per user question" (0.2.7 spec), kept as its own tiny object so it can be tested.
 *
 * A search session costs quota and time, so a second `search_web` call inside the same user turn is
 * refused politely instead of opening a second Live session. The budget is reset after every model
 * turn, which is exactly the "next question" boundary.
 */
class SearchBudget(private val maxPerTurn: Int = 1) {

    private var used = 0

    /** Reserves one search for the current turn. False when the turn's budget is already spent. */
    @Synchronized
    fun tryStart(): Boolean {
        if (used >= maxPerTurn) return false
        used += 1
        return true
    }

    @Synchronized
    fun usedThisTurn(): Int = used

    /** Called when the next user question starts (after every model turn). */
    @Synchronized
    fun reset() {
        used = 0
    }
}
