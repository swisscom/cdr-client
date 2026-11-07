package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.scheduling.BaseUploadScheduler.Companion.EXTENSION_XML
import io.github.oshai.kotlinlogging.KotlinLogging
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.ClassMode
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Verifies that the number of concurrent document uploads reaches, but never exceeds, `client.push-thread-pool-size`.
 *
 * The number of concurrent uploads is measured on the mock cdr-api: the mock holds every upload request until the test
 * releases it and keeps track of the number of requests in flight. As a request is counted only after it has been
 * received, and is no longer counted before its response is sent, the measured value never overstates the actual
 * concurrency of the client.
 *
 * NOTE: the number of `.upload` files is only logged, not asserted. A document is renamed to `.upload` before it acquires
 * an upload permit, so documents queued for upload also carry the `.upload` extension.
 */
@TestPropertySource(properties = ["client.push-thread-pool-size=${ConcurrentUploadLimitTestBase.UPLOAD_LIMIT}"])
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
internal abstract class ConcurrentUploadLimitTestBase : UploadSchedulerTestBed() {

    private val inFlight = AtomicInteger(0)
    private val peakInFlight = AtomicInteger(0)
    private val release = CountDownLatch(1)
    private val uploadsPerDocument = ConcurrentHashMap<String, AtomicInteger>()

    @AfterEach
    fun releaseHeldRequests() {
        // never leave client threads blocked in case the test failed before releasing the requests
        release.countDown()
    }

    @Test
    fun `number of concurrent uploads reaches but never exceeds the configured push thread pool size`() {
        cdrApiMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                uploadsPerDocument.computeIfAbsent(request.body?.utf8().orEmpty()) { AtomicInteger() }.incrementAndGet()
                val current = inFlight.incrementAndGet()
                peakInFlight.accumulateAndGet(current, ::maxOf)
                try {
                    if (!release.await(0, TimeUnit.MILLISECONDS)) {
                        release.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } else {
                        // keep up the pressure after the release, so the limit is also verified while uploads are being replaced
                        Thread.sleep(POST_RELEASE_UPLOAD_MILLIS)
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
                return MockResponse.Builder().code(HttpStatus.CREATED.value()).body("""{"message": "Upload successful"}""").build()
            }
        }

        val documents = (1..DOCUMENT_COUNT).map { "doc-$it" }
        documents.forEach { dropDocument(it) }

        // the limit is reached ...
        await("in-flight uploads reach the limit").atMost(Duration.ofSeconds(10)).until { inFlight.get() == UPLOAD_LIMIT }
        // ... and not exceeded while all permits are taken
        await("in-flight uploads stay at the limit")
            .during(Duration.ofSeconds(2))
            .atMost(Duration.ofSeconds(5))
            .until { inFlight.get() == UPLOAD_LIMIT && peakInFlight.get() == UPLOAD_LIMIT }
        logger.info {
            "in-flight uploads: '${inFlight.get()}'; '.upload' files (in-flight and queued): '${filesIn(sourceDir, UPLOAD_FILE_EXTENSION).size}'"
        }

        release.countDown()

        await("all documents archived").atMost(Duration.ofSeconds(30)).until { filesInTree(archiveDir, EXTENSION_XML).size == DOCUMENT_COUNT }
        await("source directory drained")
            .atMost(Duration.ofSeconds(5))
            .until { filesIn(sourceDir, EXTENSION_XML).isEmpty() && allUploadFiles().isEmpty() }

        assertEquals(UPLOAD_LIMIT, peakInFlight.get()) { "peak number of concurrent uploads" }
        assertEquals(DOCUMENT_COUNT, cdrApiMock.requestCount) { "number of upload requests" }
        assertEquals(documents.toSet(), uploadsPerDocument.keys) { "uploaded documents" }
        uploadsPerDocument.forEach { (doc, count) -> assertEquals(1, count.get()) { "number of uploads of '$doc'" } }
        assertEquals(emptyList<Any>(), filesInTree(errorDir, EXTENSION_XML)) { "documents in error directory" }
    }

    companion object {
        const val UPLOAD_LIMIT = 3
        private const val DOCUMENT_COUNT = 12
        private const val HOLD_TIMEOUT_SECONDS = 30L
        private const val POST_RELEASE_UPLOAD_MILLIS = 150L
    }

}

@ActiveProfiles("noPollingUploadScheduler")
internal class ConcurrentUploadLimitEventTriggerTest : ConcurrentUploadLimitTestBase() {
    override val uploadTasks = listOf("launchFileWatcher")
}

@ActiveProfiles("noEventTriggerUploadScheduler")
internal class ConcurrentUploadLimitPollingTriggerTest : ConcurrentUploadLimitTestBase() {
    override val uploadTasks = listOf("launchFilePoller")
}

internal class ConcurrentUploadLimitBothTriggersTest : ConcurrentUploadLimitTestBase() {
    override val uploadTasks = listOf("launchFileWatcher", "launchFilePoller")
}
