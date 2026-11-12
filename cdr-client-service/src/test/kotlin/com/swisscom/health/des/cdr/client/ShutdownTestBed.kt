package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.common.Constants.RESTART_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.scheduling.BaseUploadScheduler.Companion.EXTENSION_XML
import io.github.oshai.kotlinlogging.KotlinLogging
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.ClassMode
import org.springframework.test.annotation.DirtiesContext.HierarchyMode
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener
import org.springframework.test.context.TestExecutionListeners
import org.springframework.test.context.bean.override.BeanOverrideTestExecutionListener
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener
import org.springframework.test.context.support.DirtiesContextTestExecutionListener
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/**
 * Test bed for tests that shut down the application context while document uploads are in progress.
 *
 * Provides a mock cdr-api toolkit (requests held in flight until [release] is opened, upload counts per document) and the
 * means to close the application context and capture the [FileState] it leaves behind on disk.
 */
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
// The tests close the application context themselves, so only listeners that do not access the (closed) context after the
// test method are registered. E.g. springmockk's `MockkClearTestExecutionListener` would load a fresh application context
// after the test method, which would then pick up the documents the test has rolled back to `.xml`.
@TestExecutionListeners(
    listeners = [
        DirtiesContextBeforeModesTestExecutionListener::class,
        BeanOverrideTestExecutionListener::class,
        DependencyInjectionTestExecutionListener::class,
        DirtiesContextTestExecutionListener::class,
        ShutdownTestBed.EvictClosedContextListener::class,
    ],
    mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS,
)
internal abstract class ShutdownTestBed : UploadSchedulerTestBed() {

    @Autowired
    private lateinit var context: ConfigurableApplicationContext

    private val heldInFlight = AtomicInteger(0)
    private val release = CountDownLatch(1)
    private val uploadsPerDocument = ConcurrentHashMap<String, AtomicInteger>()

    @AfterEach
    fun releaseHeldRequests() {
        // never leave mock server threads blocked in case the test failed before releasing the requests
        release.countDown()
    }

    /** Counts the upload request for [document]; to be called by the mock cdr-api dispatcher for every request. */
    protected fun countUpload(document: String) {
        uploadsPerDocument.computeIfAbsent(document) { AtomicInteger() }.incrementAndGet()
        logger.info { "mock cdr-api received upload of '$document'" }
    }

    protected fun uploadsOf(document: String): Int = uploadsPerDocument[document]?.get() ?: 0

    /** Number of requests currently held in flight by [holdInFlight]. */
    protected fun heldInFlight(): Int = heldInFlight.get()

    /** Blocks the mock cdr-api request until [release] is opened, then responds with [response]. */
    protected fun holdInFlight(response: () -> MockResponse): MockResponse {
        heldInFlight.incrementAndGet()
        try {
            release.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            heldInFlight.decrementAndGet()
        }
        return response()
    }

    /** Lets the requests held by [holdInFlight] proceed. */
    protected fun release() = release.countDown()

    protected fun response(status: HttpStatus): MockResponse =
        MockResponse.Builder()
            .code(status.value())
            .body("""{"status": ${status.value()}, "title": "${status.reasonPhrase}"}""")
            .build()

    /**
     * Closes the application context on a separate thread. After [whileShuttingDown] the shutdown is awaited and the state of
     * the files is captured right away, i.e. before any (still running) upload threads might change it; this is what a
     * terminating client process leaves behind on disk.
     *
     * @param whileShuttingDown gets called right after the shutdown has started; receives a function that tells whether the
     * shutdown is still in progress
     */
    protected fun shutDown(whileShuttingDown: (isShuttingDown: () -> Boolean) -> Unit = {}): ShutdownResult {
        logger.info { "files before shutdown: ${FileState.capture()}" }
        val start = TimeSource.Monotonic.markNow()
        val shutdown = thread(name = "test-context-shutdown") { context.close() }
        whileShuttingDown { shutdown.isAlive }
        shutdown.join(MAX_SHUTDOWN_WAIT)
        val duration = start.elapsedNow()
        val fileState = FileState.capture()
        logger.info { "shutdown took '$duration'; files after shutdown: $fileState" }
        check(!shutdown.isAlive && !context.isActive) { "application context did not shut down within '$MAX_SHUTDOWN_WAIT'" }
        return ShutdownResult(duration, fileState)
    }

    data class ShutdownResult(val duration: kotlin.time.Duration, val fileState: FileState)

    data class FileState(
        val uploadFiles: List<String>,
        val sourceDocuments: List<String>,
        val deferred: List<String>,
        val archived: List<String>,
        val errorDocuments: List<String>,
        val errorResponses: List<String>,
    ) {
        companion object {
            fun capture(): FileState = FileState(
                uploadFiles = filesInTree(testRoot, UPLOAD_FILE_EXTENSION).map { it.name }.sorted(),
                sourceDocuments = filesIn(sourceDir, EXTENSION_XML).map { it.nameWithoutExtension }.sorted(),
                deferred = filesIn(sourceDir, RESTART_FILE_EXTENSION).map { it.nameWithoutExtension }.sorted(),
                archived = documentNames(filesInTree(archiveDir, EXTENSION_XML)),
                errorDocuments = documentNames(filesInTree(errorDir, EXTENSION_XML)),
                errorResponses = documentNames(filesInTree(errorDir, "response")),
            )

            /** Strips the `_<uuid>` suffix the client appends to archived and failed documents. */
            private fun documentNames(files: List<Path>): List<String> = files.map { it.name.substringBefore('_') }.sorted()
        }
    }

    /**
     * The tests close the application context themselves. Spring 7's test context cache would try to restart the closed (i.e.,
     * no longer running) context on its next access; evicting it from the cache right after the test method prevents that.
     */
    class EvictClosedContextListener : TestExecutionListener {
        override fun afterTestExecution(testContext: TestContext) {
            testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE)
        }
    }

    companion object {
        const val RETRY_DELAY_SECONDS = 30L
        private const val HOLD_TIMEOUT_SECONDS = 60L

        /** Longer than the retry delay plus the 1-minute timeout the client waits for active uploads on shutdown. */
        private val MAX_SHUTDOWN_WAIT: Duration = Duration.ofSeconds(RETRY_DELAY_SECONDS + 60L + 10L)

        /** Well below [RETRY_DELAY_SECONDS]; a retry backoff must not delay the shutdown. */
        val MAX_SHUTDOWN_DURATION = kotlin.time.Duration.parse("10s")
    }

}
