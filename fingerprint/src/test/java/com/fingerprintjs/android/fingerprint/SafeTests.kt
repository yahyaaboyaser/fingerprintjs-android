package com.fingerprintjs.android.fingerprint

import com.fingerprintjs.android.fingerprint.tools.safe.ExecutionTimeoutException
import com.fingerprintjs.android.fingerprint.tools.safe.Safe
import com.fingerprintjs.android.fingerprint.tools.safe.safe
import com.fingerprintjs.android.fingerprint.tools.safe.safeAsync
import com.fingerprintjs.android.fingerprint.tools.safe.safeLazy
import junit.framework.TestCase
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SafeTests {

    @After
    fun recreateExecutor() {
        Safe.executor = Safe.createThreadPoolExecutor()
    }

    @Test
    fun safeValueReturned() {
        val v = safe { 0 }
        TestCase.assertEquals(v.getOrNull(), 0)
    }

    @Test
    fun safeNestedValueReturned() {
        val v = safe { safe { 0 } }
        TestCase.assertEquals(v.getOrNull()!!.getOrNull(), 0)
    }

    @Test
    fun safeErrorRetrievable() {
        val errorId = "Hello"
        val v = safe { throw Exception(errorId) }
        val err = v.exceptionOrNull() as ExecutionException
        val errCause = err.cause!!
        TestCase.assertTrue(errCause is Exception && errCause.message == errorId)
    }

    @Test
    fun safeExecutionNeverStuck() {
        val elapsedTime = elapsedTimeMs {
            safe(timeoutMs = TimeConstants.t1) { Thread.sleep(TimeConstants.t4) }
        }
        TestCase.assertTrue(elapsedTime - TimeConstants.t1 < TimeConstants.epsilon)
    }

    @Test
    fun safeExecutionStuckThreadStackTraceReturned() {
        val res = safe(timeoutMs = TimeConstants.t1) { Thread.sleep(TimeConstants.t4) }
        val err = res.exceptionOrNull()!!
        TestCase.assertTrue(
            err is ExecutionTimeoutException
                    && err.executionThreadStackTrace != null
                    && err.executionThreadStackTrace.any { it.className == "java.lang.Thread" && it.methodName == "sleep" }
        )
    }

    @Test
    fun safeFromMultipleThreadsIsNotBlocked() {
        val countDownLatch = CountDownLatch(2)
        val elapsedTime = elapsedTimeMs {
            safeAsync(timeoutMs = TimeConstants.t2) { safe { Thread.sleep(TimeConstants.t1); countDownLatch.countDown() } }
            safeAsync(timeoutMs = TimeConstants.t2) { safe { Thread.sleep(TimeConstants.t1); countDownLatch.countDown() } }
            countDownLatch.await()
        }
        TestCase.assertTrue(elapsedTime - TimeConstants.t1 < TimeConstants.epsilon)
    }

    @Test
    fun safeThreadsAreReused() {
        for (i in 0 until 4) {
            safe { }
            TestCase.assertEquals(1, Safe.executor.poolSize)
            Thread.sleep(TimeConstants.epsilon)
        }
    }

    // this is a sad fact but we will leave it as it is
    @Test
    fun safeThreadCountGrowsIfThreadsCantInterrupt() {
        for (i in 1 until 5) {
            safe(timeoutMs = TimeConstants.epsilon) { neverReturn() }
            TestCase.assertEquals(i, Safe.executor.poolSize)
            Thread.sleep(TimeConstants.epsilon)
        }
    }

    @Test
    fun safeOuterTimeoutDominatesOverInner() {
        val elapsedTime = elapsedTimeMs {
            safe(timeoutMs = TimeConstants.t1) {
                safe(timeoutMs = TimeConstants.t2) {
                    Thread.sleep(TimeConstants.t3)
                }
            }
        }
        TestCase.assertTrue(elapsedTime - TimeConstants.t1 < TimeConstants.epsilon)
    }

    @Test
    fun safeNestedSafeInterrupted() {
        val errLvl1: Throwable?
        var errLvl2: Throwable? = null
        var errLvl3: Throwable? = null
        val countDownLatch = CountDownLatch(1)
        errLvl1 = safe(timeoutMs = TimeConstants.t1) {
            errLvl2 = safe(timeoutMs = TimeConstants.t2) {
                try {
                    Thread.sleep(TimeConstants.t3)
                } catch (t: Throwable) {
                    errLvl3 = t
                    countDownLatch.countDown()
                }
            }.exceptionOrNull()
        }.exceptionOrNull()
        countDownLatch.await()
        TestCase.assertTrue(errLvl1 is ExecutionTimeoutException)
        TestCase.assertTrue(errLvl2 is InterruptedException)
        TestCase.assertTrue(errLvl3 is InterruptedException)
    }

    @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
    @Test
    fun safeLazyEvaluatedOnce() {
        var count = 0
        val lazy = safeLazy { ++count }
        var v = lazy.getOrThrow()
        v = lazy.getOrThrow()
        TestCase.assertEquals(v, 1)
    }

    @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
    @Test
    fun safeLazyEvaluatedOnceOnError() {
        var count = 0
        val lazy = safeLazy { ++count; throw Exception() }
        var v = lazy.res
        v = lazy.res
        TestCase.assertTrue(v.exceptionOrNull() is Exception && count == 1)
    }

    @Test
    fun safeLazyExecutionNeverStuck() {
        val v = safeLazy(timeoutMs = TimeConstants.t1) { Thread.sleep(TimeConstants.t2) }
        val elapsedTime = elapsedTimeMs { v.res }
        TestCase.assertTrue(elapsedTime - TimeConstants.t1 < TimeConstants.epsilon)
    }

    @Test
    fun safeLazyEvaluatedLazily() {
        val countDownLatch = CountDownLatch(1)
        val lazy = safeLazy { countDownLatch.countDown() }
        TestCase.assertEquals(false, countDownLatch.await(TimeConstants.t1, TimeUnit.MILLISECONDS))
        lazy.res
        TestCase.assertEquals(true, countDownLatch.await(TimeConstants.t1, TimeUnit.MILLISECONDS))
    }

    @Test
    fun safeLazySynchronized() {
        val atomicInteger = AtomicInteger(0)
        val countDownLatch = CountDownLatch(1)
        val lazy = safeLazy { atomicInteger.addAndGet(1); countDownLatch.countDown() }
        safeAsync { lazy.res }
        safeAsync { lazy.res }
        countDownLatch.await()
        TestCase.assertEquals(1, atomicInteger.get())
    }

    @Test
    fun safeLazyNestedInterrupted() {
        val v1 = safeLazy(timeoutMs = TimeConstants.t1) { Thread.sleep(TimeConstants.t2) }
        val v2 = safe(timeoutMs = TimeConstants.epsilon) { v1.getOrThrow() }
        TestCase.assertTrue(v1.res.exceptionOrNull() is InterruptedException)
        TestCase.assertTrue(v2.exceptionOrNull() is ExecutionTimeoutException)
    }

    @Test
    fun safeAsyncValueReturned() {
        val countDownLatch = CountDownLatch(1)
        safeAsync { countDownLatch.countDown() }
        countDownLatch.await()
    }

    @Test
    fun safeAsyncNestedValueReturned() {
        val countDownLatch = CountDownLatch(2)
        safeAsync { safeAsync { countDownLatch.countDown() }; countDownLatch.countDown() }
        countDownLatch.await()
    }

    @Test
    fun safeAsyncErrorRetrievable() {
        val countDownLatch = CountDownLatch(1)
        val errorId = "Hello"
        safeAsync(
            onError = {
                val err = it as ExecutionException
                val errCause = err.cause!!
                TestCase.assertTrue(errCause is Exception && errCause.message == errorId)
                countDownLatch.countDown()
            }
        ) { throw Exception(errorId) }
        countDownLatch.await()
    }

    @Test
    fun safeAsyncExecutionNeverStuck() {
        val countDownLatch = CountDownLatch(1)
        val elapsedTime = elapsedTimeMs {
            safeAsync(
                timeoutMs = TimeConstants.t1,
                onError = { countDownLatch.countDown() }
            ) { Thread.sleep(TimeConstants.t4) }
            countDownLatch.await()
        }
        TestCase.assertTrue(elapsedTime - TimeConstants.t1 < TimeConstants.epsilon)
    }

    @Test
    fun safeAsyncExecutionStuckThreadStackTraceReturned() {
        val countDownLatch = CountDownLatch(1)
        var err: Throwable? = null
        safeAsync(
            timeoutMs = TimeConstants.t1,
            onError = { countDownLatch.countDown(); err = it }
        ) { Thread.sleep(TimeConstants.t4) }
        countDownLatch.await()
        val errImmutable = err
        TestCase.assertTrue(
            errImmutable is ExecutionTimeoutException
                    && errImmutable.executionThreadStackTrace != null
                    && errImmutable.executionThreadStackTrace.any { it.className == "java.lang.Thread" && it.methodName == "sleep" }
        )
    }

    @Test
    fun safeAsyncFromMultipleThreadsIsNotBlocked() {
        val countDownLatch = CountDownLatch(2)
        val elapsedTime = elapsedTimeMs {
            safeAsync { Thread.sleep(TimeConstants.t1); countDownLatch.countDown() }
            safeAsync { Thread.sleep(TimeConstants.t1); countDownLatch.countDown() }
            countDownLatch.await()
        }
        TestCase.assertTrue(elapsedTime - TimeConstants.t1 < TimeConstants.epsilon)
    }
}

private object TimeConstants {
    const val epsilon = 200L
    const val t1 = epsilon * 3
    const val t2 = t1 * 2
    const val t3 = t1 * 3
    const val t4 = t1 * 4
}

private inline fun elapsedTimeMs(block: () -> Unit): Long {
    val currentTime = System.currentTimeMillis()
    block()
    return System.currentTimeMillis() - currentTime
}

@Suppress("ControlFlowWithEmptyBody")
private fun neverReturn() {
    while (true);
}
