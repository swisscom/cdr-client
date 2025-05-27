package com.swisscom.health.des.cdr.client.http

import com.fasterxml.jackson.annotation.JsonFormat
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.time.delay
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/**
 * This is not a public API. So RPC style http endpoints will suffice for now.
 */
@RestController
class WebOperations(
    private val context: ConfigurableApplicationContext
) {

    /**
     * This endpoint essentially does the same thing as the `shutdown` actuator, only it derives an exit code
     * from the reason provided in the query parameter.
     */
    @GetMapping("/api/shutdown")
    suspend fun shutdown(@RequestParam(name = "reason") reason: String?): ResponseEntity<*> {
        val shutdownTrigger = if (reason.isNullOrBlank()) ShutdownTrigger.UNKNOWN else ShutdownTrigger.fromReason(reason)

        val response: ResponseEntity<*> =
            if (shutdownTrigger == ShutdownTrigger.UNKNOWN) {
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST.value()).let { problemDetail ->
                    ResponseEntity
                        .of(problemDetail)
                        .build<ProblemDetail>()
                }
            } else {
                ResponseEntity
                    .ok(
                        ShutdownResponse(
                            shutdownScheduledFor = Instant.now().plus(SHUTDOWN_DELAY),
                            trigger = shutdownTrigger,
                            exitCode = shutdownTrigger.exitCode
                        )
                    )
                    .also {
                        scheduleShutdown(shutdownTrigger)
                    }
            }

        return response
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun scheduleShutdown(shutdownTrigger: ShutdownTrigger) {
        if (SHUTDOWN_GUARD.tryLock()) {
            GlobalScope.launch {
                logger.info { "Shutdown requested for reason: [$shutdownTrigger]" }
                // Wait a bit to allow the http response to be sent before shutting down
                delay(SHUTDOWN_DELAY)
                val exitCode = SpringApplication.exit(context).let { contextExitCode ->
                    val exitCode =
                        if (contextExitCode != 0) {
                            logger.error { "Spring application context did not exit cleanly, exit code: [$contextExitCode]" }
                            contextExitCode
                        } else {
                            logger.debug { "Spring application context exited cleanly." }
                            shutdownTrigger.exitCode
                        }
                    exitCode
                }
                exitProcess(exitCode)
            }
        } else {
            logger.info { "Shutdown already scheduled, ignoring request for reason: [$shutdownTrigger]" }
        }
    }

    enum class ShutdownTrigger(val reason: String, val exitCode: Int) {
        CONFIG_CHANGE("configurationChange", CONFIG_CHANGE_EXIT_CODE),
        UNKNOWN("unknown", UNKNOWN_EXIT_CODE);

        companion object {
            fun fromReason(reason: String): ShutdownTrigger {
                return entries.firstOrNull { it.reason == reason } ?: UNKNOWN
            }
        }
    }

    data class ShutdownResponse(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        val shutdownScheduledOn: Instant = Instant.now(),
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        val shutdownScheduledFor: Instant,
        val trigger: ShutdownTrigger,
        val exitCode: Int,
    )

    companion object {
        @JvmStatic
        private val SHUTDOWN_DELAY = Duration.ofMillis(500)

        @JvmStatic
        private val SHUTDOWN_GUARD = Mutex()

        private const val CONFIG_CHANGE_EXIT_CODE = 29
        private const val UNKNOWN_EXIT_CODE = 31
    }
}
