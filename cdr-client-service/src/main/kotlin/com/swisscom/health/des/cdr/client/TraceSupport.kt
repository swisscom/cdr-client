package com.swisscom.health.des.cdr.client

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.Tracer.SpanInScope
import kotlinx.coroutines.ThreadContextElement
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext

/**
 * Kotlin streams do not play nicely with Micrometer tracing, so we provide some helper functions
 * to start and continue spans from stream processing steps.
 */
object TraceSupport {

    @OptIn(ExperimentalContracts::class)
    inline fun <A> startSpan(tracer: Tracer, spanName: String, block: () -> A): Pair<A, Span> {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        val span: Span = tracer.spanBuilder().name(spanName).start()

        // TODO: Commented out for the time being. It appears the Azure Application Insights agent enables telemetry collection,
        //   which causes below test to fail. We need to investigate whether the unclosed span (we close the "Span in Scope" but
        //   not the span itself) causes a resource leak and if so, find another solution. See #37831.
//        require(span.toString().startsWith("PropagatedSpan")) {
//            "Expected an OpenTelemetry `PropagatedSpan` (which is a noop implementation) but got a ${span.javaClass.canonicalName} instead."
//        }

        return tracer.withSpan(span).use { _ ->
            block()
        } to span
    }

    @OptIn(ExperimentalContracts::class)
    inline fun <A> continueSpan(tracer: Tracer, span: Span, block: () -> A): Pair<A, Span> {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        return tracer.withSpan(span).use { _ ->
            block()
        } to span
    }

}

internal class SpanContextElement(private val span: Span, private val tracer: Tracer) : ThreadContextElement<SpanInScope> {
    override val key: CoroutineContext.Key<SpanContextElement>
        get() = Key

    override fun updateThreadContext(context: CoroutineContext): SpanInScope = tracer.withSpan(span)

    override fun restoreThreadContext(context: CoroutineContext, oldState: SpanInScope) = oldState.close()

    override fun toString(): String = "$Key=$span"

    companion object Key : CoroutineContext.Key<SpanContextElement> {
        override fun toString(): String = "SpanInScope"
    }

}
