package com.swisscom.health.des.cdr.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
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

    @JvmStatic
    val TRACER: Tracer = GlobalOpenTelemetry.get().getTracer("cdr-client-trace-support")

    inline fun <A> withSpan(spanName: String, block: () -> A): A {
        val span: Span = TRACER.spanBuilder(spanName).startSpan()
        return span.makeCurrent().use { _ ->
            logger.info { "Starting span: '$span'" }
            block().also {
                logger.info { "Ending span: '$span'" }
            }
        }
    }

    @OptIn(ExperimentalContracts::class)
    inline fun <A> startSpan(spanName: String, block: () -> A): Pair<A, Span> {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        val span: Span = TRACER.spanBuilder(spanName).startSpan()

        return span.makeCurrent().use { _ ->
            logger.info { "Starting span: '$span'" }
            block().also {
                logger.info { "Ending span: '$span'" }
            }
        } to span
    }

    @OptIn(ExperimentalContracts::class)
    inline fun <A> continueSpan(span: Span, block: () -> A): Pair<A, Span> {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        return span.makeCurrent().use { _ ->
            logger.info { "Continuing span: '$span'" }
            block().also {
                logger.info { "Ending continuation of span: '$span'" }
            }
        } to span
    }

}

//internal class SpanContextElement(private val span: Span) : ThreadContextElement<Scope> {
//    override val key: CoroutineContext.Key<SpanContextElement>
//        get() = Key
//
//    override fun updateThreadContext(context: CoroutineContext): Scope = span.makeCurrent()
//
//    override fun restoreThreadContext(context: CoroutineContext, oldState: Scope) = oldState.close()
//
//    override fun toString(): String = "$Key=$span"
//
//    companion object Key : CoroutineContext.Key<SpanContextElement> {
//        override fun toString(): String = "SpanInScope"
//    }
//
//}
