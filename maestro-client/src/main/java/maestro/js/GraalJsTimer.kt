package maestro.js

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

data class TimerInfo(val scheduledTimeMillis: Long, val future: ScheduledFuture<*>)

class GraalJsTimer {

    companion object {
        private const val MAX_WAIT_FOR_TIMERS_TIMEOUT_MS = 60_000L
        private const val MAX_TIMER_AGE_MS = 30_000L
        private val LOGGER = Logger.getLogger(GraalJsTimer::class.java.name)
    }

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val timeouts = ConcurrentHashMap<Int, TimerInfo>()
    private var timeoutCounter = 0
    private var activeTimersCount = AtomicInteger(0)
    private var activeTimers = CountDownLatch(0)

    val setTimeout = ProxyExecutable { args ->
        val callback = args[0] as Value
        val delay = (args[1] as Value).asLong()
        val restArgs = args.drop(2).toTypedArray()
        setTimeout(callback, delay, *restArgs)
    }

    val clearTimeout = ProxyExecutable { args ->
        val timeoutId = (args[0] as Value).asInt()
        clearTimeout(timeoutId)
        null
    }

    private fun setTimeout(callback: Value, delay: Long, vararg args: Any?): Int {
        val timeoutId = timeoutCounter++
        
        synchronized(this) {
            if (activeTimersCount.getAndIncrement() == 0) {
                activeTimers = CountDownLatch(1)
            }
        }
        
        val future = scheduler.schedule({
            try {
                callback.executeVoid(*args)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                timeouts.remove(timeoutId)
                if (activeTimersCount.decrementAndGet() == 0) {
                    activeTimers.countDown()
                }
            }
        }, delay, TimeUnit.MILLISECONDS)

        timeouts[timeoutId] = TimerInfo(System.currentTimeMillis(), future)
        return timeoutId
    }

    private fun clearTimeout(timeoutId: Int) {
        timeouts.remove(timeoutId)?.let { timerInfo ->
            timerInfo.future.cancel(false)
            if (activeTimersCount.decrementAndGet() == 0) {
                activeTimers.countDown()
            }
        }
    }

    fun cancelOverdueTimers(maxTimerAgeMillis: Long) {
        val currentTime = System.currentTimeMillis()
        timeouts.forEach { (timeoutId, timerInfo) ->
            if (currentTime - timerInfo.scheduledTimeMillis > maxTimerAgeMillis) {
                if (timerInfo.future.cancel(false)) {
                    timeouts.remove(timeoutId)
                    if (activeTimersCount.decrementAndGet() == 0) {
                        activeTimers.countDown()
                    }
                    val overdueTime = currentTime - timerInfo.scheduledTimeMillis - maxTimerAgeMillis
                    LOGGER.warning("Cancelled overdue timer (ID: $timeoutId) which was ${overdueTime}ms over the max age limit.")
                }
            }
        }
    }

    fun waitForActiveTimers(timeout: Long = 30_000) {
        if (activeTimersCount.get() > 0) {
            cancelOverdueTimers(MAX_TIMER_AGE_MS)
            val waitTime = if (timeout > MAX_WAIT_FOR_TIMERS_TIMEOUT_MS) {
                MAX_WAIT_FOR_TIMERS_TIMEOUT_MS
            } else {
                timeout
            }
            val success = activeTimers.await(waitTime, TimeUnit.MILLISECONDS)
            if (!success && activeTimersCount.get() > 0) {
                // It's possible that cancelOverdueTimers cleared all timers already
                if (activeTimersCount.get() > 0) {
                    LOGGER.warning("waitForActiveTimers timed out after ${waitTime}ms, ${activeTimersCount.get()} timers are still active.")
                }
            }
        }
    }

    fun close() {
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
    }
} 