package com.swisscom.health.des.cdr.client

import kotlinx.coroutines.ThreadContextElement
import org.slf4j.MDC
import java.util.UUID
import kotlin.coroutines.CoroutineContext

internal object LogCorrelation {
    const val TRACE_ID_KEY: String = "traceId"

    fun currentTraceId(): String = MDC.get(TRACE_ID_KEY).orEmpty()

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
