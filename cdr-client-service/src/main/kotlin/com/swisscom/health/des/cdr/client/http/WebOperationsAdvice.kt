package com.swisscom.health.des.cdr.client.http

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

private val logger = KotlinLogging.logger {}

@ControllerAdvice
internal class WebOperationsAdvice {

    class ServerError(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    class BadRequest(
        message: String,
        cause: Throwable? = null,
        val props: Map<String, Any>? = null
    ) : Exception(message, cause)

    @ExceptionHandler
    fun handleError(error: ServerError): ProblemDetail {
        logger.error(error.cause) { "server error: $error" }
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR).apply {
            detail = error.message
        }
    }

    @ExceptionHandler
    fun handleError(error: BadRequest): ProblemDetail {
        logger.info { "bad request: $error" }
        return ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            detail = error.message
            properties = error.props
        }
    }

    @ExceptionHandler
    fun handle(e: ErrorResponseException): ProblemDetail {
        logger.info { "error response due to exception: $e" }
        return ProblemDetail.forStatus(e.statusCode).apply {
            detail = e.message
        }
    }

}