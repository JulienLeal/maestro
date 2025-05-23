package maestro.js

import com.google.common.truth.Truth.assertThat
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.never
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class GraalJsTimerTest {

    private lateinit var graalJsTimer: GraalJsTimer
    private lateinit var mockCallback: Value

    // For capturing log messages
    private lateinit var testLogHandler: TestLogHandler
    private lateinit var logger: Logger

    // Access private constants via reflection
    private val MAX_WAIT_FOR_TIMERS_TIMEOUT_MS: Long by lazy {
        getPrivateConst("MAX_WAIT_FOR_TIMERS_TIMEOUT_MS")
    }
    private val MAX_TIMER_AGE_MS: Long by lazy {
        getPrivateConst("MAX_TIMER_AGE_MS")
    }

    private fun getPrivateConst(name: String): Long {
        val field = GraalJsTimer.Companion::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getLong(GraalJsTimer.Companion)
    }


    @BeforeEach
    fun setUp() {
        graalJsTimer = GraalJsTimer()
        mockCallback = mock(Value::class.java)
        `when`(mockCallback.canExecute()).thenReturn(true)

        // Setup logger to capture warnings
        logger = Logger.getLogger(GraalJsTimer::class.java.name)
        testLogHandler = TestLogHandler()
        logger.addHandler(testLogHandler)
        logger.level = Level.WARNING // Ensure warnings are captured
    }

    @AfterEach
    fun tearDown() {
        graalJsTimer.close()
        logger.removeHandler(testLogHandler)
        logger.level = Level.INFO // Reset logger level
    }

    @Test
    @Timeout(65, unit = TimeUnit.SECONDS) // Test timeout slightly larger than MAX_WAIT_FOR_TIMERS_TIMEOUT_MS
    fun `waitForActiveTimers respects MAX_WAIT_FOR_TIMERS_TIMEOUT_MS`() {
        val longDelay = MAX_WAIT_FOR_TIMERS_TIMEOUT_MS + 10_000L // e.g., 70s
        val callTimeout = MAX_WAIT_FOR_TIMERS_TIMEOUT_MS + 20_000L // e.g., 80s

        graalJsTimer.setTimeout(mockCallback, longDelay)
        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(1)

        val startTime = System.currentTimeMillis()
        graalJsTimer.waitForActiveTimers(callTimeout)
        val duration = System.currentTimeMillis() - startTime

        // Assert that it waited for approximately MAX_WAIT_FOR_TIMERS_TIMEOUT_MS
        // Allowing a small delta for execution overhead
        assertThat(duration).isAtLeast(MAX_WAIT_FOR_TIMERS_TIMEOUT_MS - 1000) // e.g. 59s
        assertThat(duration).isAtMost(MAX_WAIT_FOR_TIMERS_TIMEOUT_MS + 5000)  // e.g. 65s to account for CI slowness

        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(1) // Timer should still be active (not cancelled)
        verify(mockCallback, never()).executeVoid(anyVararg()) // Callback should not have been executed yet

        assertThat(testLogHandler.records).hasSize(1)
        val logRecord = testLogHandler.records.first()
        assertThat(logRecord.level).isEqualTo(Level.WARNING)
        assertThat(logRecord.message).contains("waitForActiveTimers timed out after ${MAX_WAIT_FOR_TIMERS_TIMEOUT_MS}ms")
        assertThat(logRecord.message).contains("1 timers are still active")

        // Clean up the long timer explicitly to avoid it running after the test if not shut down by close() quickly enough
        graalJsTimer.clearTimeout(0) // Assuming timeoutId is 0
        graalJsTimer.waitForActiveTimers(100) // Ensure it's cleared
        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(0)
    }

    @Test
    fun `cancelOverdueTimers cancels timers older than maxTimerAgeMillis`() {
        val maxTimerAgeMs = 500L
        val timerDelay = 1000L // Longer than maxTimerAgeMs

        val timeoutId = graalJsTimer.setTimeout(mockCallback, timerDelay)
        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(1)

        // Wait for a period longer than maxTimerAgeMs but shorter than timerDelay
        Thread.sleep(maxTimerAgeMs + 100)

        graalJsTimer.cancelOverdueTimers(maxTimerAgeMs)

        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(0) // Timer should be cancelled
        verify(mockCallback, never()).executeVoid(anyVararg()) // Callback should not have been executed

        assertThat(testLogHandler.records).hasSize(1)
        val logRecord = testLogHandler.records.first()
        assertThat(logRecord.level).isEqualTo(Level.WARNING)
        assertThat(logRecord.message).contains("Cancelled overdue timer (ID: $timeoutId)")
        assertThat(logRecord.message).contains("over the max age limit")

        // Ensure the timer is not present in timeouts map
        val timeoutsMap = graalJsTimer.javaClass.getDeclaredField("timeouts").let {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            it.get(graalJsTimer) as Map<Int, Any>
        }
        assertThat(timeoutsMap).doesNotContainKey(timeoutId)
    }

    @Test
    @Timeout(35, unit = TimeUnit.SECONDS) // Needs to be > MAX_TIMER_AGE_MS (30s) + buffer
    fun `waitForActiveTimers proceeds if timers are cancelled by cancelOverdueTimers`() {
        // This test checks the scenario where waitForActiveTimers calls cancelOverdueTimers internally,
        // and that internal call cancels a timer. MAX_TIMER_AGE_MS is used by that internal call.
        // We set a timer that will be older than MAX_TIMER_AGE_MS by the time cancelOverdueTimers is run.

        val timerDelay = MAX_TIMER_AGE_MS + 500L // e.g., 30.5s if MAX_TIMER_AGE_MS is 30s
        val waitForActiveTimersCallTimeout = MAX_TIMER_AGE_MS + 2000L // e.g., 32s

        // Adjust test timeout to be longer than MAX_TIMER_AGE_MS
        // The @Timeout annotation cannot use a variable, so this test might be slow if MAX_TIMER_AGE_MS is large.
        // For default 30s, this test will take ~30s.

        val timeoutId = graalJsTimer.setTimeout(mockCallback, timerDelay)
        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(1)

        // Wait until slightly past MAX_TIMER_AGE_MS so the timer becomes overdue
        // but not so long that the timer itself fires.
        Thread.sleep(MAX_TIMER_AGE_MS + 100) // Wait for 30.1s

        val startTime = System.currentTimeMillis()
        // waitForActiveTimers will call cancelOverdueTimers(MAX_TIMER_AGE_MS)
        graalJsTimer.waitForActiveTimers(waitForActiveTimersCallTimeout)
        val duration = System.currentTimeMillis() - startTime

        // It should return quickly because the timer was cancelled by the internal call to cancelOverdueTimers.
        // Max duration should be significantly less than waitForActiveTimersCallTimeout,
        // and also less than MAX_TIMER_AGE_MS because the cancellation should be prompt.
        // Allow some time for the scheduling and cancellation logic.
        assertThat(duration).isLessThan(1000L) // Should be very quick, e.g. < 1s
        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(0) // Timer should be cancelled
        verify(mockCallback, never()).executeVoid(anyVararg()) // Callback should not have been executed

        assertThat(testLogHandler.records).hasSize(1)
        val logRecord = testLogHandler.records.first()
        assertThat(logRecord.level).isEqualTo(Level.WARNING)
        assertThat(logRecord.message).contains("Cancelled overdue timer (ID: $timeoutId)")

        // This test relies on calling cancelOverdueTimers directly before waitForActiveTimers
        // to simulate the behavior with a controllable maxTimerAge.
        // A more integrated test would require modifying GraalJsTimer to allow easier testing of constants.
    }

    @Test
    @Timeout(5, unit = TimeUnit.SECONDS) // Test timeout
    fun `waitForActiveTimers times out if non-overdue timers are still active`() {
        // Timer delay is shorter than MAX_TIMER_AGE_MS, so it won't be cancelled as overdue.
        // Timer delay is longer than the callTimeout for waitForActiveTimers.
        // So, waitForActiveTimers should time out based on its callTimeout (or MAX_WAIT_FOR_TIMERS_TIMEOUT_MS if smaller).

        val timerDelay = MAX_TIMER_AGE_MS - 1000L // e.g., 29s (if MAX_TIMER_AGE_MS is 30s)
                                                 // Ensure timerDelay is positive
                                                 .coerceAtLeast(500L)
        val callTimeoutForWaitForActiveTimers = 200L // This will be the effective timeout for waiting.

        // Ensure callTimeoutForWaitForActiveTimers is less than MAX_WAIT_FOR_TIMERS_TIMEOUT_MS
        // so that callTimeoutForWaitForActiveTimers is the one that dictates the timeout.
        assertThat(callTimeoutForWaitForActiveTimers).isLessThan(MAX_WAIT_FOR_TIMERS_TIMEOUT_MS)
        // Ensure timer is not immediately overdue
        assertThat(timerDelay).isLessThan(MAX_TIMER_AGE_MS)
        // Ensure timer will outlive the wait
        assertThat(timerDelay).isGreaterThan(callTimeoutForWaitForActiveTimers)


        val timeoutId = graalJsTimer.setTimeout(mockCallback, timerDelay)
        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(1)

        val startTime = System.currentTimeMillis()
        graalJsTimer.waitForActiveTimers(callTimeoutForWaitForActiveTimers)
        val duration = System.currentTimeMillis() - startTime

        // Assert that it waited for approximately callTimeoutForWaitForActiveTimers
        assertThat(duration).isAtLeast(callTimeoutForWaitForActiveTimers - 100)
        assertThat(duration).isAtMost(callTimeoutForWaitForActiveTimers + 500) // Allow for CI slowness

        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(1) // Timer should still be active
        verify(mockCallback, never()).executeVoid(anyVararg()) // Callback should not have been executed yet

        assertThat(testLogHandler.records).hasSize(1)
        val logRecord = testLogHandler.records.first()
        assertThat(logRecord.level).isEqualTo(Level.WARNING)
        assertThat(logRecord.message).contains("waitForActiveTimers timed out after ${callTimeoutForWaitForActiveTimers}ms")
        assertThat(logRecord.message).contains("1 timers are still active")

        // Clean up
        graalJsTimer.clearTimeout(timeoutId)
        graalJsTimer.waitForActiveTimers(100)
        assertThat(graalJsTimer.activeTimersCount.get()).isEqualTo(0)
    }

    /**
     * A simple handler to capture log records for assertions.
     */
    class TestLogHandler : Handler() {
        val records = mutableListOf<LogRecord>()
        override fun publish(record: LogRecord?) {
            record?.let { records.add(it) }
        }
        override fun flush() {}
        override fun close() {}
    }
}
