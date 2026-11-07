package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Duration

/**
 * Verifies the graceful shutdown of the document upload flow for uploads that fail with an `IOException`, and thus enter the
 * retry-with-backoff loop:
 *
 * * (F) the connection gets dropped before shutdown starts -> document is in retry backoff when shutdown starts; the backoff
 *   is cut short and the document is renamed back to `.xml`
 * * (G) the upload request is in flight when shutdown starts and runs into the read timeout during shutdown -> shutdown waits
 *   for the request to fail; the document is not retried, but renamed back to `.xml`
 */
@TestPropertySource(
    properties = [
        "client.read-timeout-ms=${GracefulUploadShutdownOnIoErrorTestBase.READ_TIMEOUT_MILLIS}",
        // long enough to show that shutdown cuts the backoff short, but shorter than the 1-minute shutdown timeout
        "client.retry-delay=${ShutdownTestBed.RETRY_DELAY_SECONDS}s",
    ]
)
internal abstract class GracefulUploadShutdownOnIoErrorTestBase : ShutdownTestBed() {

    @Test
    fun `graceful shutdown rolls back uploads that failed with an IOException`() {
        cdrApiMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val document = request.body?.utf8().orEmpty()
                countUpload(document)
                return when (document) {
                    DOC_DISCONNECTED -> MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
                    // never responds before the client's read timeout; released after the test
                    DOC_READ_TIMEOUT -> holdInFlight { response(HttpStatus.CREATED) }
                    else -> response(HttpStatus.CREATED)
                }
            }
        }

        // (F) connection dropped, then waiting for its retry
        dropDocument(DOC_DISCONNECTED)
        await("($DOC_DISCONNECTED) is in retry backoff")
            .atMost(Duration.ofSeconds(10))
            .until { uploadsOf(DOC_DISCONNECTED) >= 1 && sourceDir.resolve("$DOC_DISCONNECTED.$UPLOAD_FILE_EXTENSION").toFile().exists() }
        // give the client time to process the IOException; OkHttp might also transparently retry on a dropped connection
        Thread.sleep(SETTLE_MILLIS)
        val disconnectedUploadsBeforeShutdown = uploadsOf(DOC_DISCONNECTED)

        // (G) in flight, will run into the read timeout after shutdown has started
        dropDocument(DOC_READ_TIMEOUT)
        await("($DOC_READ_TIMEOUT) is in flight").atMost(Duration.ofSeconds(10)).until { heldInFlight() == 1 }

        var shutdownWaitedForInFlightUpload = false
        val (shutdownDuration, afterShutdown) = shutDown { isShuttingDown ->
            // (G) is still waiting for its response at this point in time
            Thread.sleep(SETTLE_MILLIS)
            shutdownWaitedForInFlightUpload = isShuttingDown()
        }

        assertAll(
            { assertTrue(shutdownWaitedForInFlightUpload) { "shutdown must wait for the in-flight upload to complete" } },
            {
                assertTrue(shutdownDuration < MAX_SHUTDOWN_DURATION) {
                    "shutdown took '$shutdownDuration', expected less than '$MAX_SHUTDOWN_DURATION'; the retry backoff must not delay the shutdown"
                }
            },
            { assertEquals(emptyList<String>(), afterShutdown.uploadFiles) { "orphaned .upload files" } },
            {
                assertEquals(disconnectedUploadsBeforeShutdown, uploadsOf(DOC_DISCONNECTED)) {
                    "'$DOC_DISCONNECTED' must not be retried during shutdown"
                }
            },
            { assertEquals(1, uploadsOf(DOC_READ_TIMEOUT)) { "'$DOC_READ_TIMEOUT' must not be retried during shutdown" } },
            { assertEquals(listOf(DOC_DISCONNECTED, DOC_READ_TIMEOUT), afterShutdown.sourceDocuments) { "documents rolled back to .xml" } },
            { assertEquals(emptyList<String>(), afterShutdown.archived) { "archived documents" } },
            { assertEquals(emptyList<String>(), afterShutdown.errorDocuments) { "documents in error directory" } },
            { assertEquals(emptyList<String>(), afterShutdown.deferred) { "documents deferred until restart" } },
        )
    }

    companion object {
        const val READ_TIMEOUT_MILLIS = 3_000L
        private const val SETTLE_MILLIS = 1_000L

        private const val DOC_DISCONNECTED = "f-disconnected"
        private const val DOC_READ_TIMEOUT = "g-read-timeout"
    }

}

@ActiveProfiles("noPollingUploadScheduler")
internal class GracefulUploadShutdownOnIoErrorEventTriggerTest : GracefulUploadShutdownOnIoErrorTestBase() {
    override val uploadTasks = listOf("launchFileWatcher")
}

@ActiveProfiles("noEventTriggerUploadScheduler")
internal class GracefulUploadShutdownOnIoErrorPollingTriggerTest : GracefulUploadShutdownOnIoErrorTestBase() {
    override val uploadTasks = listOf("launchFilePoller")
}

internal class GracefulUploadShutdownOnIoErrorBothTriggersTest : GracefulUploadShutdownOnIoErrorTestBase() {
    override val uploadTasks = listOf("launchFileWatcher", "launchFilePoller")
}
