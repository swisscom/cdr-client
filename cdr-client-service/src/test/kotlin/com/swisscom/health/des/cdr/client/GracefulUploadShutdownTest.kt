package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
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
 * Verifies the graceful shutdown of the document upload flow. At the time the application context gets closed, the documents
 * are in the following states:
 *
 * * (A) upload request in flight; the cdr-api accepts the document after shutdown has started -> document is archived
 * * (B) upload request in flight; the cdr-api rejects the document (`400`) -> document is moved to the error directory,
 *   along with a `.response` file
 * * (C) upload request in flight; the cdr-api responds with a non-retryable client error (`422`) -> document is renamed
 *   to `.clientRestartRequired`
 * * (D) in retry-with-backoff after a server error (`500`) -> backoff is cut short; the document is renamed back to `.xml`
 * * (E) queued for upload, waiting for an upload permit -> document is renamed back to `.xml` and is never uploaded
 *
 * After shutdown no `.upload` file must be left behind, and the shutdown must not be held up by the retry backoff of (D).
 */
@TestPropertySource(
    properties = [
        // A, B, C and D occupy all permits (D keeps its permit during the retry backoff), so the E documents must queue up
        "client.push-thread-pool-size=4",
        // long enough to show that shutdown cuts the backoff short, but shorter than the 1-minute shutdown timeout
        "client.retry-delay=${ShutdownTestBed.RETRY_DELAY_SECONDS}s",
    ]
)
internal abstract class GracefulUploadShutdownTestBase : ShutdownTestBed() {

    @Test
    fun `graceful shutdown completes or rolls back all uploads and leaves no upload files behind`() {
        cdrApiMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val document = request.body?.utf8().orEmpty()
                countUpload(document)
                return when (document) {
                    DOC_RETRYING -> response(HttpStatus.INTERNAL_SERVER_ERROR)
                    DOC_UPLOADED -> holdInFlight { response(HttpStatus.CREATED) }
                    DOC_REJECTED -> holdInFlight { response(HttpStatus.BAD_REQUEST) }
                    DOC_DEFERRED -> holdInFlight { response(HttpStatus.UNPROCESSABLE_CONTENT) }
                    else -> response(HttpStatus.CREATED)
                }
            }
        }

        // (D) first, so it gets an upload permit and keeps it while waiting for its retry
        dropDocument(DOC_RETRYING)
        await("($DOC_RETRYING) is in retry backoff")
            .atMost(Duration.ofSeconds(10))
            .until { uploadsOf(DOC_RETRYING) == 1 && sourceDir.resolve("$DOC_RETRYING.$UPLOAD_FILE_EXTENSION").toFile().exists() }

        // (A), (B), (C) take the remaining permits and are held in flight
        listOf(DOC_UPLOADED, DOC_REJECTED, DOC_DEFERRED).forEach { dropDocument(it) }
        await("upload requests in flight").atMost(Duration.ofSeconds(10)).until { heldInFlight() == IN_FLIGHT_COUNT }

        // (E) queue up behind the permit limit
        QUEUED_DOCS.forEach { dropDocument(it) }
        await("queued documents are renamed to .upload")
            .atMost(Duration.ofSeconds(10))
            .until { filesIn(sourceDir, UPLOAD_FILE_EXTENSION).size == 1 + IN_FLIGHT_COUNT + QUEUED_DOCS.size }

        var shutdownWaitedForInFlightUploads = false
        val (shutdownDuration, afterShutdown) = shutDown { isShuttingDown ->
            // let shutdown begin while the uploads are still in flight, then let the cdr-api respond
            Thread.sleep(IN_FLIGHT_DURING_SHUTDOWN_MILLIS)
            shutdownWaitedForInFlightUploads = isShuttingDown()
            if (shutdownWaitedForInFlightUploads) {
                release()
            }
        }

        assertAll(
            { assertTrue(shutdownWaitedForInFlightUploads) { "shutdown must wait for the in-flight uploads to complete" } },
            {
                assertTrue(shutdownDuration < MAX_SHUTDOWN_DURATION) {
                    "shutdown took '$shutdownDuration', expected less than '$MAX_SHUTDOWN_DURATION'; the retry backoff must not delay the shutdown"
                }
            },
            { assertEquals(emptyList<String>(), afterShutdown.uploadFiles) { "orphaned .upload files" } },
            // (A) uploaded and archived
            { assertEquals(1, uploadsOf(DOC_UPLOADED)) { "uploads of '$DOC_UPLOADED'" } },
            { assertEquals(listOf(DOC_UPLOADED), afterShutdown.archived) { "archived documents" } },
            // (B) rejected and moved to the error directory
            { assertEquals(1, uploadsOf(DOC_REJECTED)) { "uploads of '$DOC_REJECTED'" } },
            { assertEquals(listOf(DOC_REJECTED), afterShutdown.errorDocuments) { "documents in error directory" } },
            { assertEquals(listOf(DOC_REJECTED), afterShutdown.errorResponses) { "response files in error directory" } },
            // (C) deferred until restart
            { assertEquals(1, uploadsOf(DOC_DEFERRED)) { "uploads of '$DOC_DEFERRED'" } },
            { assertEquals(listOf(DOC_DEFERRED), afterShutdown.deferred) { "documents deferred until restart" } },
            // (D) retry cancelled, (E) never uploaded; both rolled back so they get uploaded again after a restart
            { assertEquals(1, uploadsOf(DOC_RETRYING)) { "'$DOC_RETRYING' must not be retried during shutdown" } },
            {
                assertEquals(emptyMap<String, Int>(), QUEUED_DOCS.associateWith { uploadsOf(it) }.filterValues { it > 0 }) {
                    "queued documents uploaded after shutdown has started"
                }
            },
            { assertEquals((QUEUED_DOCS + DOC_RETRYING).sorted(), afterShutdown.sourceDocuments) { "documents rolled back to .xml" } },
        )
    }

    private companion object {
        private const val IN_FLIGHT_DURING_SHUTDOWN_MILLIS = 1_000L

        private const val DOC_UPLOADED = "a-uploaded"
        private const val DOC_REJECTED = "b-rejected"
        private const val DOC_DEFERRED = "c-deferred"
        private const val DOC_RETRYING = "d-retrying"
        private const val IN_FLIGHT_COUNT = 3
        private val QUEUED_DOCS = listOf("e-queued-1", "e-queued-2", "e-queued-3")
    }

}

@ActiveProfiles("noPollingUploadScheduler")
internal class GracefulUploadShutdownEventTriggerTest : GracefulUploadShutdownTestBase() {
    override val uploadTasks = listOf("launchFileWatcher")
}

@ActiveProfiles("noEventTriggerUploadScheduler")
internal class GracefulUploadShutdownPollingTriggerTest : GracefulUploadShutdownTestBase() {
    override val uploadTasks = listOf("launchFilePoller")
}

internal class GracefulUploadShutdownBothTriggersTest : GracefulUploadShutdownTestBase() {
    override val uploadTasks = listOf("launchFileWatcher", "launchFilePoller")
}
