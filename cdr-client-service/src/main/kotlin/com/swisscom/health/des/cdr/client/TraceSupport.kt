package com.swisscom.health.des.cdr.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

val logger = KotlinLogging.logger {}

/**
 * Kotlin streams do not play nicely with Micrometer tracing, so we provide some helper functions
 * to start and continue spans from stream processing steps.
 */
object TraceSupport {

    inline fun <A> withSpan(tracer: Tracer, spanName: String, block: () -> A): A {
        val span: Span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.INTERNAL).startSpan()
        return span.makeCurrent().use { _ ->
            logger.trace { "Starting span: '$span'" }
            block().also {
                logger.trace { "Ending span: '$span'" }
            }
        }
    }

    @OptIn(ExperimentalContracts::class)
    inline fun <A> startSpan(tracer: Tracer, spanName: String, block: () -> A): Pair<A, Span> {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        val span: Span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.INTERNAL).startSpan()

        return span.makeCurrent().use { _ ->
            logger.trace { "Starting span: '$span'" }
            block().also {
                logger.trace { "Ending span: '$span'" }
            }
        } to span
    }

    @OptIn(ExperimentalContracts::class)
    inline fun <A> continueSpan(span: Span, block: () -> A): Pair<A, Span> {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        return span.makeCurrent().use { _ ->
            logger.trace { "Continuing span: '$span'" }
            block().also {
                logger.trace { "Ending continuation of span: '$span'" }
            }
        } to span
    }

}
