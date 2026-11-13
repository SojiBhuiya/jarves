package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 0.2.7: "one search per user question", and one only — even under concurrent tool calls. */
class SearchBudgetTest {

    @Test
    fun theFirstSearchOfATurnIsAllowedAndTheSecondIsNot() {
        val budget = SearchBudget()

        assertTrue(budget.tryStart())
        assertEquals(1, budget.usedThisTurn())
        assertFalse("a second search in the same turn is refused", budget.tryStart())
        assertEquals(1, budget.usedThisTurn())
    }

    @Test
    fun theNextQuestionGetsAFreshBudget() {
        val budget = SearchBudget()
        budget.tryStart()
        budget.tryStart()

        budget.reset()
        assertEquals(0, budget.usedThisTurn())
        assertTrue("after a new turn a search is allowed again", budget.tryStart())
    }

    @Test
    fun concurrentToolCallsStillSpendTheBudgetOnce() {
        val budget = SearchBudget()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val granted = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(threads) {
            pool.execute {
                start.await()
                if (budget.tryStart()) granted.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("exactly one search may start", 1, granted.get())
    }

    @Test
    fun theLimitIsConfigurableForTestsOnly() {
        val budget = SearchBudget(maxPerTurn = 2)
        assertTrue(budget.tryStart())
        assertTrue(budget.tryStart())
        assertFalse(budget.tryStart())
    }
}
