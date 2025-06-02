package com.swisscom.health.des.cdr.client.http

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.http.WebOperations.ShutdownTrigger
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail

@ExtendWith(MockKExtension::class)
internal class WebOperationsTest {

    @MockK
    lateinit var context: ConfigurableApplicationContext

    @MockK
    lateinit var healthEndpoint: HealthEndpoint

    @MockK
    lateinit var objectMapper: com.fasterxml.jackson.databind.ObjectMapper

    lateinit var webOperations: WebOperations

    @BeforeEach
    fun setUp() {
        webOperations = WebOperations(context = context, healthEndpoint = healthEndpoint, objectMapper = objectMapper)
    }

    @Test
    @Disabled(
        "triggers the creation of a coroutine in global scope that does a `System.exit()` " +
                "after a delay which may kill the VM running the test before it can finish, which in turn fails the build"
    )
    fun `test shutdown with valid reason`() = runTest {
        val response = webOperations.shutdown(ShutdownTrigger.CONFIG_CHANGE.reason)
        val shutdownResponse = assertInstanceOf<DTOs.ShutdownResponse>(response.body)
        assertEquals(ShutdownTrigger.CONFIG_CHANGE.name, shutdownResponse.trigger)
        assertEquals(ShutdownTrigger.CONFIG_CHANGE.exitCode, shutdownResponse.exitCode)
    }

    @Test
    fun `test shutdown with empty reason`() = runTest {
        val response = webOperations.shutdown("")
        val shutdownResponse = assertInstanceOf<ProblemDetail>(response.body)
        assertEquals(HttpStatus.BAD_REQUEST.value(), shutdownResponse.status)
    }

    @Test
    fun `test shutdown with unknown reason`() = runTest {
        val response = webOperations.shutdown("go-figure")
        val shutdownResponse = assertInstanceOf<ProblemDetail>(response.body)
        assertEquals(HttpStatus.BAD_REQUEST.value(), shutdownResponse.status)
    }

}
