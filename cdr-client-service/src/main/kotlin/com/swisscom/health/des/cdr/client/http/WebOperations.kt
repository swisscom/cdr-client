package com.swisscom.health.des.cdr.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.swisscom.health.des.cdr.client.common.Constants.SHUTDOWN_DELAY
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.toCdrClientConfig
import com.swisscom.health.des.cdr.client.config.toDto
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import com.swisscom.health.des.cdr.client.handler.ShutdownService
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_INDICATOR_NAME
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.SystemHealth
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * This is not a public API. So RPC style http endpoints will suffice for now.
 */
@RestController
internal class WebOperations(
    private val healthEndpoint: HealthEndpoint,
    private val objectMapper: ObjectMapper,
    private val shutdownService: ShutdownService,
    private val configWriter: ConfigurationWriter,
    private val config: CdrClientConfig,
) {

    @GetMapping("api/service-configuration")
    suspend fun getServiceConfiguration(): ResponseEntity<DTOs.CdrClientConfig> =
        ResponseEntity
            .ok(
                config.toDto()
            )

    @PutMapping("api/service-configuration")
    suspend fun updateServiceConfiguration(@RequestBody config: DTOs.CdrClientConfig): ResponseEntity<*> = runCatching {
        configWriter.updateClientServiceConfiguration(config.toCdrClientConfig()).let { result ->
            when (result) {
                is ConfigurationWriter.Result.Success -> {
                    ResponseEntity
                        .ok(config)
                }

                is ConfigurationWriter.Result.Failure -> {
                    ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).let { problemDetail ->
                        problemDetail.detail = "Invalid configuration"
                        problemDetail.properties = result.errors
                        ResponseEntity
                            .of(problemDetail)
                            .build<ProblemDetail>()
                    }
                }
            }
        }
    }.getOrElse { error: Throwable ->
        logger.error(error) { "Failed to update service configuration" }
        ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR).let { problemDetail ->
            problemDetail.detail = "Failed to update service configuration: $error"
            ResponseEntity
                .of(problemDetail)
                .build<ProblemDetail>()
        }
    }


    /**
     * This endpoint returns the status of the client service. It uses the health endpoint and the [health indicators][HealthIndicators]
     * registered in the application context. The health status of the different indicators is translated into an application-specific
     * [status code][DTOs.StatusResponse.StatusCode].
     */
    @GetMapping("api/status")
    suspend fun status(): ResponseEntity<*> = runCatching {
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
    }.getOrElse { error: Throwable ->
        logger.error(error) { "Failed to retrieve service status" }
        ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR).let { problemDetail ->
            problemDetail.detail = "Failed to retrieve service status: $error"
            ResponseEntity
                .of(problemDetail)
                .build<ProblemDetail>()
        }
    }

    /**
     * This endpoint essentially does the same thing as the `shutdown` actuator, only it derives an exit code
     * from the reason provided in the query parameter.
     */
    @GetMapping("/api/shutdown")
    suspend fun shutdown(@RequestParam(name = "reason") reason: String?): ResponseEntity<*> = runCatching {
        val shutdownTrigger = if (reason.isNullOrBlank()) ShutdownService.ShutdownTrigger.UNKNOWN else ShutdownService.ShutdownTrigger.fromReason(reason)

        val response: ResponseEntity<*> =
            if (shutdownTrigger == ShutdownService.ShutdownTrigger.UNKNOWN) {
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).let { problemDetail ->
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
                        shutdownService.scheduleShutdown(shutdownTrigger)
                    }
            }

        return response
    }.getOrElse { error: Throwable ->
        logger.error(error) { "Failed to schedule shutdown" }
        ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR).let { problemDetail ->
            problemDetail.detail = "Failed to schedule shutdown: $error"
            ResponseEntity
                .of(problemDetail)
                .build<ProblemDetail>()
        }
    }

}
