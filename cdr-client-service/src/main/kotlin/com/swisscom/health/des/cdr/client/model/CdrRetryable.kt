package com.swisscom.health.des.cdr.client.model

import org.springframework.core.retry.Retryable
import java.net.URL

sealed class RetryableCdrOperation<A> : Retryable<A>

internal class RetryableHttpOperation<A>(private val operationName: String, val url: URL, private val block: () -> A): RetryableCdrOperation<A>() {

    override fun execute(): A {
        return block()
    }

    override fun getName(): String {
        return operationName
    }

}
