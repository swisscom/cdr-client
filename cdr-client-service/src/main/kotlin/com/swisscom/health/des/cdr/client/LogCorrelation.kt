package com.swisscom.health.des.cdr.client

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID
import kotlin.coroutines.CoroutineContext

internal object LogCorrelation {
    const val TRACE_ID_KEY: String = "traceId"

    fun currentTraceId(): String = MDC.get(TRACE_ID_KEY).orEmpty()

    /**
     * Runs [block] under a freshly generated trace ID.
     *
     * IMPORTANT: [block] must be a plain, non-suspending piece of code that is guaranteed to run to completion on the
     * calling thread without ever suspending. The MDC `put`/`remove` pair below is only correct if entry and exit happen
     * on the very same thread; a coroutine that suspends and resumes on a different thread (e.g. because it hops onto
     * another dispatcher) would leave the trace ID behind on the original thread and corrupt whatever thread it resumes
     * on. If you need trace correlation inside a `suspend` function, use [withNewTraceIdSuspending] instead, which relies
     * on [MdcTraceIdContextElement]/[kotlinx.coroutines.withContext] and is therefore safe across suspension points.
     */
    inline fun <A> withNewTraceId(block: () -> A): A {
        val traceId = UUID.randomUUID().toString()
        val previousTraceId = MDC.get(TRACE_ID_KEY)

        return try {
            MDC.put(TRACE_ID_KEY, traceId)
            block()
        } finally {
            restorePreviousTraceId(previousTraceId)
        }
    }

    /**
     * Coroutine-safe equivalent of [withNewTraceId]: runs [block] under a freshly generated trace ID, propagating and
     * restoring it correctly across suspension points and dispatcher/thread switches via [MdcTraceIdContextElement].
     * Use this variant whenever [block] is a `suspend` lambda, i.e. whenever it may suspend and later resume on a
     * different thread.
     */
    suspend fun <A> withNewTraceIdSuspending(block: suspend () -> A): A =
        withContext(contextElement(UUID.randomUUID().toString())) {
            block()
        }

    fun contextElement(traceId: String = currentTraceId()): MdcTraceIdContextElement = MdcTraceIdContextElement(traceId)

    private fun restorePreviousTraceId(previousTraceId: String?) {
        if (previousTraceId == null) {
            MDC.remove(TRACE_ID_KEY)
        } else {
            MDC.put(TRACE_ID_KEY, previousTraceId)
        }
    }
}

internal class MdcTraceIdContextElement(private val traceId: String) : ThreadContextElement<String?> {
    override val key: CoroutineContext.Key<MdcTraceIdContextElement>
        get() = Key

    override fun updateThreadContext(context: CoroutineContext): String? {
        val previousTraceId = MDC.get(LogCorrelation.TRACE_ID_KEY)
        if (traceId.isBlank()) {
            MDC.remove(LogCorrelation.TRACE_ID_KEY)
        } else {
            MDC.put(LogCorrelation.TRACE_ID_KEY, traceId)
        }
        return previousTraceId
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        if (oldState == null) {
            MDC.remove(LogCorrelation.TRACE_ID_KEY)
        } else {
            MDC.put(LogCorrelation.TRACE_ID_KEY, oldState)
        }
    }

    override fun toString(): String = "$Key=$traceId"

    companion object Key : CoroutineContext.Key<MdcTraceIdContextElement> {
        override fun toString(): String = "MdcTraceId"
    }
}
