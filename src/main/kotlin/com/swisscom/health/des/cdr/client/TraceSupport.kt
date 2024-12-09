package com.swisscom.health.des.cdr.client

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

        // TODO: Comment out for the time being. It appears the Azure Application Insights agent enables telemetry collection,
        //   which causes below test to fail. We need to investigate whether the unclosed span (we close the "Span in Scope" but
        //   not the span itself) causes a resource leak and if so, find another solution. See #37831.
//        require(span.toString().startsWith("PropagatedSpan")) {
//            "Expected an OpenTelemetry `PropagatedSpan` (which is a noop implementation) but got a ${span.javaClass.canonicalName} instead."
//        }

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
