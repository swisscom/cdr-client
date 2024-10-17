package com.swisscom.health.des.cdr.clientvm

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.Tracer.SpanInScope
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract


object TraceSupport {

    @OptIn(ExperimentalContracts::class)
    inline fun <A> startSpan(tracer: Tracer, spanName: String, block: () -> A): Pair<A, Span> {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        val span: Span = tracer.spanBuilder().name(spanName).start()

        require(span.toString().startsWith("PropagatedSpan")) {
            "Expected an OpenTelemetry `PropagatedSpan` (which is a noop implementation) but got a ${span.javaClass.canonicalName} instead."
        }

        val spanInScope: SpanInScope = tracer.withSpan(span)

        return spanInScope.use { _ ->
            block()
        } to span
    }

    @OptIn(ExperimentalContracts::class)
    inline fun <A> continueSpan(tracer: Tracer, span: Span, block: () -> A): Pair<A, Span> {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        val spanInScope: SpanInScope = tracer.withSpan(span)

        return spanInScope.use { _ ->
            block()
        } to span
    }

}
