package com.swisscom.health.des.cdr.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_INDICATOR_NAME
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.time.delay
import org.springframework.boot.SpringApplication
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.SystemHealth
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
internal class WebOperations(
    private val context: ConfigurableApplicationContext,
    private val healthEndpoint: HealthEndpoint,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping("api/status")
    suspend fun status(): ResponseEntity<DTOs.StatusResponse> {
        val healthStatus: SystemHealth = healthEndpoint.health() as SystemHealth
        logger.debug { "Health endpoint response: '${objectMapper.writeValueAsString(healthStatus)}'" }

        val status = when (healthStatus.components[FILE_SYNCHRONIZATION_INDICATOR_NAME]?.status?.code) {
            healthStatus.components[FILE_SYNCHRONIZATION_INDICATOR_NAME]?.status?.code -> DTOs.StatusResponse.StatusCode.SYNCHRONIZING
            healthStatus.components[FILE_SYNCHRONIZATION_INDICATOR_NAME]?.status?.code -> DTOs.StatusResponse.StatusCode.DISABLED
            else -> DTOs.StatusResponse.StatusCode.UNKNOWN
            // TODO: add checks for error scenarios: configuration errors, CDR API not reachable, IdP not reachable, etc.
            //   all other error scenarios would probably prevent the client service from starting altogether;
            //   remember result of last attempt to reach remote endpoints instead of pinging them?
        }

        return ResponseEntity.ok(
            DTOs.StatusResponse(
                statusCode = status,
            )
        )
    }

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
                        DTOs.ShutdownResponse(
                            shutdownScheduledFor = Instant.now().plus(SHUTDOWN_DELAY),
                            trigger = shutdownTrigger.name,
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
                logger.info { "Shutdown requested for reason: '$shutdownTrigger'" }
                // Wait a bit to allow the http response to be sent before shutting down
                delay(SHUTDOWN_DELAY)
                // previously producing the exit code was a single line:
                // `exitProcess(SpringApplication.exit(context, { shutdownTrigger.exitCode }))`
                // however, there must be some sort of race condition present; half the time the exit code was 0 instead of the code specified in the
                // `shutdownTrigger`
                SpringApplication.exit(context).let { contextExitCode ->
                    if (contextExitCode != 0) {
                        logger.warn { "Spring application context did not exit cleanly, exit code: '$contextExitCode'" }
                    }
                }
                exitProcess(shutdownTrigger.exitCode)
            }
        } else {
            logger.info { "Shutdown already scheduled, ignoring request for reason: '$shutdownTrigger'" }
        }
    }

    internal enum class ShutdownTrigger(val reason: String, val exitCode: Int) {
        CONFIG_CHANGE("configurationChange", CONFIG_CHANGE_EXIT_CODE),
        UNKNOWN("unknown", UNKNOWN_EXIT_CODE);

        companion object {
            fun fromReason(reason: String): ShutdownTrigger {
                return entries.firstOrNull { it.reason == reason } ?: UNKNOWN
            }
        }
    }

    private companion object {
        @JvmStatic
        private val SHUTDOWN_DELAY = Duration.ofMillis(500)

        @JvmStatic
        private val SHUTDOWN_GUARD = Mutex()

        private const val CONFIG_CHANGE_EXIT_CODE = 29
        private const val UNKNOWN_EXIT_CODE = 31
    }
}
