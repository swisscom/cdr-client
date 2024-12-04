package com.swisscom.health.des.cdr.client.scheduling

import com.mayakapps.kache.ObjectKache
import com.swisscom.health.des.cdr.client.TraceSupport.continueSpan
import com.swisscom.health.des.cdr.client.TraceSupport.startSpan
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.PushFileHandling
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsDirectoryWatcherEvent
import io.github.irgaly.kfswatch.KfsEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Objects.isNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

@Service
@Profile("!noEventTriggerUploadScheduler")
class EventTriggerUploadScheduler(
    private val config: CdrClientConfig,
    private val tracer: Tracer,
    @Qualifier("limitedParallelismCdrUploadsDispatcher")
    private val cdrUploadsDispatcher: CoroutineDispatcher,
    @Value("\${management.tracing.sampling.probability:1.0}")
    private val samplerProbability: Double,
    pushFileHandling: PushFileHandling,
    processingInProgressCache: ObjectKache<String, Path>,
) : BaseUploadScheduler(
    config = config,
    pushFileHandling = pushFileHandling,
    cdrUploadsDispatcher = cdrUploadsDispatcher,
    processingInProgressCache = processingInProgressCache,
    tracer = tracer,
) {

    @PostConstruct
    @Suppress("UnusedPrivateMember")
    private fun failIfTelemetrySamplingIsEnabled() {
        if (samplerProbability > ZERO_SAMPLING_THRESHOLD) {
            logger.error {
                "Telemetry sampling is enabled (sampling probability is set to $samplerProbability). Currently we cannot support telemetry " +
                        "sampling without introducing a memory leak due to the lack of framework integration of micrometer/open-telemetry with Kotlin " +
                        "coroutines/asynchronous flows. You need to disable telemetry sampling."
            }
            error("Telemetry sampling is enabled. Please set the configuration property `management.tracing.sampling.probability` to 0.0")
        }
    }

    // NOTE: The short initial delay is there to make it as likely as possible for the task to be already running by the time the SpringBoot test
    // sets the per-test configuration on the spy of the `CdrClientConfig`, which includes the test source directory. If the original task starts
    // too late it will pick up the test source directory, and we have two instances of the watcher consuming events from that directory.
    @Scheduled(initialDelay = 0L, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun launchFileWatcher() {
        withContext(Dispatchers.IO) {
            launch {
                KfsDirectoryWatcher(CoroutineScope(coroutineContext)).run {
                    uploadFiles(watchForNewFilesToUpload(this))
                }
            }
        }
    }

    private suspend fun watchForNewFilesToUpload(watcher: KfsDirectoryWatcher): Flow<Pair<Path, Span>> {
        addWatchedPaths(watcher, config.customer.map { it.sourceFolder })

        return watcher.onEventFlow
            .onCompletion { error: Throwable? ->
                logger.info { "File system event flow terminated ${if (error == null) "." else "with error: '${error.message}'"}" }
            }
            .map { event: KfsDirectoryWatcherEvent ->
                startSpan(tracer, "file system event") {
                    event
                }
            }
            .filter { (event: KfsDirectoryWatcherEvent, span) ->
                continueSpan(tracer, span) {
                    when (event.event) {
                        KfsEvent.Create -> true.also {
                            logger.debug {
                                "file created: [${
                                    Path.of(event.targetDirectory, event.path).absolute()
                                }]"
                            }
                        }

                        KfsEvent.Delete -> false.also {
                            logger.debug {
                                "file deleted: [${
                                    Path.of(event.targetDirectory, event.path).absolute()
                                }]; ignored, was probably us"
                            }
                        }

                        KfsEvent.Modify -> false.also {
                            logger.debug {
                                "file modified: [${
                                    Path.of(event.targetDirectory, event.path).absolute()
                                }]; who's messing?"
                            }
                        }
                    }
                }.first
            }
            .map { (event: KfsDirectoryWatcherEvent, span) ->
                continueSpan(tracer, span) { Path.of(event.targetDirectory, event.path).absolute() }
            }
    }

    private suspend fun addWatchedPaths(watcher: KfsDirectoryWatcher, paths: List<Path>): Unit = paths.forEach { path ->
        watcher.add(path.absolutePathString()).also { logger.info { "Watching source folder for files: [${path.absolutePathString()}]" } }
    }

}

@Service
@Profile("!noPollingUploadScheduler")
class PollingUploadScheduler(
    private val config: CdrClientConfig,
    private val tracer: Tracer,
    @Qualifier("limitedParallelismCdrUploadsDispatcher")
    private val cdrUploadsDispatcher: CoroutineDispatcher,
    @Value("\${management.tracing.sampling.probability:1.0}")
    private val samplerProbability: Double,
    pushFileHandling: PushFileHandling,
    processingInProgressCache: ObjectKache<String, Path>,
) : BaseUploadScheduler(
    config = config,
    pushFileHandling = pushFileHandling,
    cdrUploadsDispatcher = cdrUploadsDispatcher,
    processingInProgressCache = processingInProgressCache,
    tracer = tracer,
) {

    @PostConstruct
    @Suppress("UnusedPrivateMember")
    private fun failIfTelemetrySamplingIsEnabled() {
        if (samplerProbability > ZERO_SAMPLING_THRESHOLD) {
            logger.error {
                "Telemetry sampling is enabled (sampling probability is set to $samplerProbability). Currently we cannot support telemetry " +
                        "sampling without introducing a memory leak due to the lack of framework integration of micrometer/open-telemetry with Kotlin " +
                        "coroutines/asynchronous flows. You need to disable telemetry sampling."
            }
            error("Telemetry sampling is enabled. Please set the configuration property `management.tracing.sampling.probability` to 0.0")
        }
    }

    @Scheduled(initialDelay = 0L, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun launchFilePoller() {
        config.scheduleDelay.toString().substring(2).replace("""(\d[HMS])(?!$)""".toRegex(), "$1 ").lowercase().let{ humanReadableDelay ->
            config.customer.forEach {
                logger.info { "Polling source folder for files every '$humanReadableDelay': '${it.sourceFolder}'" }
            }
        }
        withContext(Dispatchers.IO) {
            launch {
                uploadFiles(pollForNewFilesToUpload())
            }
        }
    }

    private suspend fun pollForNewFilesToUpload(): Flow<Pair<Path, Span>> =
        flow {
            while (true) {
                config.customer
                    .asSequence()
                    .map {
                        startSpan(tracer, "poll directory ${it.sourceFolder}") {
                            logger.debug { "Polling source folder for files: ${it.sourceFolder}" }
                            it.sourceFolder
                        }
                    }
                    .flatMap { (sourceFolder, span) ->
                        sourceFolder
                            .listDirectoryEntries()
                            .asSequence()
                            .sortedBy { Files.readAttributes(it, BasicFileAttributes::class.java).lastModifiedTime() }
                            .map { path -> path to span }
                    }.forEach { (path, span) ->
                        emit(path.absolute() to span)
                    }
                logger.debug { "Next poll in '${config.scheduleDelay}'" }
                delay(config.scheduleDelay)
            }
        }
            .onCompletion { error: Throwable? ->
                logger.info { "File system polling flow terminated ${if (error == null) "." else "with error: '${error.message}'"}" }
            }
            .buffer(capacity = 100, onBufferOverflow = BufferOverflow.SUSPEND)
            .shareIn(
                scope = CoroutineScope(coroutineContext),
                started = SharingStarted.Lazily,
                replay = 0,
            )

}

abstract class BaseUploadScheduler(
    private val config: CdrClientConfig,
    private val pushFileHandling: PushFileHandling,
    @Qualifier("limitedParallelismCdrUploadsDispatcher")
    private val cdrUploadsDispatcher: CoroutineDispatcher,
    private val processingInProgressCache: ObjectKache<String, Path>,
    private val tracer: Tracer,
) {
    protected suspend fun uploadFiles(pathFlow: Flow<Pair<Path, Span>>): Unit =
        pathFlow
            .filter { (fileOrDir: Path, span) ->
                continueSpan(tracer, span) {
                    logger.debug {
                        "new item [${fileOrDir.name}] in [${fileOrDir.parent}] ; file ${
                            if (fileOrDir.isRegularFile()) "is"
                            else "is not"
                        } a regular file and will be ${
                            if (fileOrDir.isRegularFile()) "processed" else "ignored"
                        }"
                    }
                    fileOrDir.isRegularFile()
                }.first
            }
            .filter { (file: Path, span) ->
                continueSpan(tracer, span) {
                    logger.debug {
                        "new file [${file.name}] in [${file.parent}] ; file ${
                            if (file.extension == EXTENSION_XML) "ends"
                            else "does not end"
                        } with '.$EXTENSION_XML' and will be ${
                            if (file.extension == EXTENSION_XML) "processed" else "ignored"
                        }"
                    }
                    file.extension == EXTENSION_XML
                }.first
            }
            .filter { (file: Path, span) ->
                continueSpan(tracer, span) {
                    isNull(processingInProgressCache.put(file.absolutePathString(), file))
                        .also { isNotProcessing ->
                            if (!isNotProcessing) {
                                logger.debug { "file [${file.name}] in [${file.parent}] is already being processed; ignoring" }
                            }
                        }
                }.first
            }
            .onEach { (file: Path, span) ->
                continueSpan(tracer, span) {
                    logger.info { "processing file [${file.name}] in [${file.parent}]" }
                    val connector = config.customer.first { it.sourceFolder == file.parent }
                    withContext(coroutineContext) {
                        launch(cdrUploadsDispatcher) {
                            continueSpan(tracer, span) {
                                pushFileHandling.uploadFile(file, connector)
                            }
                        }
                    }
                }
            }.collect()

    protected companion object {
        protected const val EXTENSION_XML = "xml"
        @JvmStatic
        protected val ZERO_SAMPLING_THRESHOLD: Double = 0.0
    }

}
