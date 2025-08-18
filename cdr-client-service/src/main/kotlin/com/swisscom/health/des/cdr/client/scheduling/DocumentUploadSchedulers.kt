package com.swisscom.health.des.cdr.client.scheduling

import com.mayakapps.kache.ObjectKache
import com.swisscom.health.des.cdr.client.TraceSupport.continueSpan
import com.swisscom.health.des.cdr.client.TraceSupport.startSpan
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.FileBusyTester
import com.swisscom.health.des.cdr.client.config.getConnectorForSourceFile
import com.swisscom.health.des.cdr.client.handler.RetryUploadFileHandling
import com.swisscom.health.des.cdr.client.handler.SchedulingValidationService
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsDirectoryWatcherEvent
import io.github.irgaly.kfswatch.KfsEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Objects.isNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

@Service
@Profile("!noEventTriggerUploadScheduler")
@ConditionalOnProperty(prefix = "client", name = ["file-synchronization-enabled"])
@Suppress("LongParameterList")
internal class EventTriggerUploadScheduler(
    private val config: CdrClientConfig,
    private val schedulingValidationService: SchedulingValidationService,
    private val tracer: Tracer,
    @Qualifier("limitedParallelismCdrUploadsDispatcher")
    cdrUploadsDispatcher: CoroutineDispatcher,
    retryUploadFileHandling: RetryUploadFileHandling,
    processingInProgressCache: ObjectKache<String, Path>,
    fileBusyTester: FileBusyTester
) : BaseUploadScheduler(
    config = config,
    retryUploadFileHandling = retryUploadFileHandling,
    cdrUploadsDispatcher = cdrUploadsDispatcher,
    processingInProgressCache = processingInProgressCache,
    fileBusyTester = fileBusyTester,
) {

    // NOTE: The scheduled tasks are racing the SpringBoot/integration tests; we need to give the tests enough time to update the client configuration
    // for the test scenario before the scheduled tasks start; the shorter we make the initial delay, the higher the likelihood that the tests fail.
    @Scheduled(initialDelay = DEFAULT_INITIAL_DELAY_MILLIS, fixedDelay = DEFAULT_RESTART_DELAY_MILLIS, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun launchFileWatcher(): Unit = runCatching {
        if (schedulingValidationService.isSchedulingAllowed) {
            logger.info { "Starting file watcher process..." }
            config.customer.forEach { connector ->
                logger.info { "Watching source directory: '${connector.sourceFolder}'" }
                connector.effectiveDocTypeFolders.filter { map -> map.value.sourceFolder != null }
                    .forEach { (_, typeFolders) -> logger.info { "Watching additional source directory: '${connector.effectiveSourceFolder(typeFolders)}'" } }
            }

            coroutineScope {
                launch(Dispatchers.Default) {
                    KfsDirectoryWatcher(scope = this).run {
                        uploadFiles(watchForNewFilesToUpload(this))
                    }
                }
            }
        }
    }.fold(
        onSuccess = { },
        onFailure = { t: Throwable ->
//            tracer.withSpan(null)
            when (t) {
                is CancellationException -> logger.info { "Shutting down file watcher task." }.also { throw t }
                else -> {
                    logger.error { "File watcher process terminated with error: $t" }
                    logger.info { "Restarting file watcher process in $DEFAULT_RESTART_DELAY_SECONDS seconds..." }
                }
            }
        }
    )

    private suspend fun watchForNewFilesToUpload(watcher: KfsDirectoryWatcher): Flow<Pair<Path, Span>> {
        val sourceTypeDirectories: List<Path> = config.customer.map { it.getAllSourceDocTypeFolders() }.flatten()
        val directories = config.customer.map { it.sourceFolder } + sourceTypeDirectories

        addWatchedPaths(watcher, directories)

        return watcher.onEventFlow
            .onCompletion { error: Throwable? ->
//                tracer.withSpan(null)
                when (error) {
                    !is CancellationException -> logger.error {
                        "File system event flow terminated${if (error != null) " with error: '${error::class}'; message: '${error.message}'" else "."}"
                    }

                    else -> logger.debug { "File system event flow terminated." }
                }
            }
            .map { event: KfsDirectoryWatcherEvent ->
                startSpan(tracer, "file system event") {
                    event
                }
            }
            .filter { (event: KfsDirectoryWatcherEvent, span) ->
                continueSpan(span) {
                    when (event.event) {
                        KfsEvent.Create -> true.also {
                            logger.debug {
                                "file created: '${
                                    Path.of(event.targetDirectory, event.path).absolute()
                                }'"
                            }
                        }

                        KfsEvent.Delete -> false.also {
                            logger.debug {
                                "file deleted: '${
                                    Path.of(event.targetDirectory, event.path).absolute()
                                }'; ignored, was probably us"
                            }
                        }

                        KfsEvent.Modify -> false.also {
                            logger.debug {
                                "file modified: '${
                                    Path.of(event.targetDirectory, event.path).absolute()
                                }'; who's messing?"
                            }
                        }
                    }
                }.first
            }
            .map { (event: KfsDirectoryWatcherEvent, span) ->
                continueSpan(span) { Path.of(event.targetDirectory, event.path).absolute() }
            }
    }

    private suspend fun addWatchedPaths(watcher: KfsDirectoryWatcher, paths: List<Path>): Unit =
        paths.forEach { path ->
            watcher.add(path.absolutePathString())
        }

}

@Service
@Profile("!noPollingUploadScheduler")
@ConditionalOnProperty(prefix = "client", name = ["file-synchronization-enabled"])
@Suppress("LongParameterList")
internal class PollingUploadScheduler(
    private val config: CdrClientConfig,
    private val schedulingValidationService: SchedulingValidationService,
    @param:Qualifier("limitedParallelismCdrUploadsDispatcher")
    private val cdrUploadsDispatcher: CoroutineDispatcher,
    private val tracer: Tracer,
    retryUploadFileHandling: RetryUploadFileHandling,
    processingInProgressCache: ObjectKache<String, Path>,
    fileBusyTester: FileBusyTester,
) : BaseUploadScheduler(
    config = config,
    retryUploadFileHandling = retryUploadFileHandling,
    cdrUploadsDispatcher = cdrUploadsDispatcher,
    processingInProgressCache = processingInProgressCache,
    fileBusyTester = fileBusyTester,
) {

    // NOTE: The scheduled tasks are racing the SpringBoot/integration tests; we need to give the tests enough time to update the client configuration
    // for the test scenario before the scheduled tasks start; the shorter we make the initial delay, the higher the likelihood that the tests fail.
    @Scheduled(initialDelay = DEFAULT_INITIAL_DELAY_MILLIS, fixedDelay = DEFAULT_RESTART_DELAY_MILLIS, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun launchFilePoller(): Unit = runCatching {
        if (schedulingValidationService.isSchedulingAllowed) {
            logger.info { "Starting directory polling process..." }
            config.scheduleDelay.toString().substring(2).replace("""(\d[HMS])(?!$)""".toRegex(), "$1 ").lowercase().let { humanReadableDelay ->
                config.customer.forEach { connector ->
                    logger.info { "Polling source directory every '$humanReadableDelay': '${connector.sourceFolder}'" }
                    connector.effectiveDocTypeFolders.filter { map -> map.value.sourceFolder != null }
                        .forEach { (_, typeFolders) ->
                            logger.info {
                                "Polling additional source directory every '$humanReadableDelay': '${connector.effectiveSourceFolder(typeFolders)}'"
                            }
                        }
                }
            }
            coroutineScope {
                withContext(Dispatchers.Default) {
                    val fileFlow = pollForNewFilesToUpload(this)
                    launch {
                        uploadFiles(fileFlow)
                    }
                }
            }
        }
    }.fold(
        onSuccess = { },
        onFailure = { t: Throwable ->
//            tracer.withSpan(null)
            when (t) {
                is CancellationException -> logger.info { "Shutting down file polling task." }.also { throw t }
                else -> {
                    logger.error { "Directory polling process terminated with error: $t" }
                    logger.info { "Restarting directory polling process in $DEFAULT_RESTART_DELAY_SECONDS seconds..." }
                }
            }
        }
    )

    private fun pollForNewFilesToUpload(scope: CoroutineScope): Flow<Pair<Path, Span>> =
        flow {
            while (true) {
                config.customer
                    .asSequence()
                    .map {
                        startSpan(tracer, "poll directory ${it.sourceFolder}") {
                            logger.debug { "Polling source directory for files: ${it.sourceFolder}" }
                            it.getAllSourceDocTypeFolders().forEach { dir -> logger.debug { "Polling additional source directory for files: $dir" } }
                            it
                        }
                    }
                    .flatMap { (connector, _) ->
                        val allSourceFolders: List<Path> = listOf(connector.sourceFolder) + connector.getAllSourceDocTypeFolders()
                        allSourceFolders.flatMap { dir ->
                            dir.listDirectoryEntries()
                                .asSequence()
                                .sortedBy { Files.readAttributes(it, BasicFileAttributes::class.java).lastModifiedTime() }
                                .map { path -> startSpan(tracer, "handle file found in ${path.parent}") { path } }
                        }
                    }.forEach { (path, span) ->
                        emit(path.absolute() to span)
                    }
                logger.debug { "Next poll in '${config.scheduleDelay}'" }
                delay(config.scheduleDelay)
            }
        }
            .onCompletion { error: Throwable? ->
//                DefaultSpan.create(SpanContext.getInvalid())
//                tracer.withSpan(null)
                // `shareIn(..)` makes this a hot flow which never terminates unless an error occurs, or it is explicitly canceled
                when (error) {
                    !is CancellationException -> logger.error {
                        "File system polling flow terminated${if (error != null) " with error: '${error::class}'; message: '${error.message}'" else "."}"
                    }

                    else -> logger.debug { "File system polling flow terminated." }
                }
            }
            .buffer(capacity = 100, onBufferOverflow = BufferOverflow.SUSPEND)
            .shareIn(
                scope = scope,
                started = SharingStarted.Lazily,
                replay = 0,
            )

}

internal abstract class BaseUploadScheduler(
    private val config: CdrClientConfig,
    private val retryUploadFileHandling: RetryUploadFileHandling,
    @param:Qualifier("limitedParallelismCdrUploadsDispatcher")
    private val cdrUploadsDispatcher: CoroutineDispatcher,
    private val processingInProgressCache: ObjectKache<String, Path>,
    private val fileBusyTester: FileBusyTester,
) {

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    protected suspend fun uploadFiles(pathFlow: Flow<Pair<Path, Span>>): Unit = coroutineScope {
        pathFlow
            .filter { (fileOrDir: Path, span: Span) ->
                continueSpan(span) {
                    logger.debug {
                        "new item '${fileOrDir.name}' in '['${fileOrDir.parent}']' ; file ${
                            if (fileOrDir.isRegularFile()) "is"
                            else "is not"
                        } a regular file and will be ${
                            if (fileOrDir.isRegularFile()) "processed" else "ignored"
                        }"
                    }
                    fileOrDir.isRegularFile()
                }.first
            }
            .filter { (file: Path, span: Span) ->
                continueSpan(span) {
                    logger.debug {
                        "new file '${file.name}' in '${file.parent}' ; file ${
                            if (file.extension == EXTENSION_XML) "ends"
                            else "does not end"
                        } with '.$EXTENSION_XML' and will be ${
                            if (file.extension == EXTENSION_XML) "processed" else "ignored"
                        }"
                    }
                    file.extension == EXTENSION_XML
                }.first
            }
            .filter { (file: Path, span: Span) ->
                continueSpan(span) {
                    isNull(processingInProgressCache.put(file.absolutePathString(), file))
                        .also { isNotProcessing ->
                            if (!isNotProcessing) {
                                logger.info { "file '${file.name}' in '${file.parent}' is already being processed; ignoring" }
                            }
                        }
                }.first
            }
            .onEach { (file: Path, span: Span) ->
                continueSpan(span) {
                    logger.info { "queuing '${file}' for upload" }
                    launch(span.asContextElement()) {
                        runCatching {
                            dispatchForUpload(file)
                        }.fold(
                            onSuccess = { uploaded -> logger.info { "'$file' ${if (uploaded) "uploaded or error handled" else "not uploaded"}" } },
                            onFailure = { t: Throwable ->
                                when (t) {
                                    is CancellationException -> throw t
                                    else -> logger.error { "failed to upload '${file}': ${t.message}" }
                                }
                            }
                        )
                        isNull(processingInProgressCache.remove(file.absolutePathString())).also { missing ->
                            if (missing) {
                                logger.warn {
                                    "File '${file.absolutePathString()}' was not in the processing cache when we tried to remove it from the cache! " +
                                            "It appears that we have a bug in our state management."
                                }
                            }
                        }
                    }
                }
            }
            .onCompletion { error: Throwable? ->
//                tracer.withSpan(null)
                when (error) {
                    !is CancellationException -> logger.error {
                        "Upload flow subscription terminated${if (error != null) " with error: '${error::class}'; message: '${error.message}'" else "."}"
                    }

                    else -> logger.debug { "Upload flow subscription terminated." }
                }
            }
            .collect()
    }

    private suspend fun dispatchForUpload(file: Path): Boolean =
        config.customer.getConnectorForSourceFile(file).let { connector ->
            if (!file.isBusy()) {
                // limit parallelism of uploads; cdrUploadsDispatcher is defined as a limited parallelism IO dispatcher
                withContext(cdrUploadsDispatcher) {
                    retryUploadFileHandling.uploadRetrying(file, connector)
                }
                true
            } else {
                logger.warn { "'$file' is still busy after '${config.fileBusyTestTimeout}'; giving up; file will be picked up again on next poll" }
                false
            }
        }

    private suspend fun Path.isBusy(): Boolean =
        runCatching {
            withTimeout(config.fileBusyTestTimeout.toMillis()) {
                while (fileBusyTester.isBusy(this@isBusy)) {
                    logger.debug { "'${this@isBusy}' is still busy; waiting '${config.fileBusyTestInterval}' for it to become available" }
                    delay(config.fileBusyTestInterval)
                }
                false
            }
        }.fold(
            onSuccess = { it },
            onFailure = { t: Throwable ->
                when (t) {
                    is TimeoutCancellationException -> true // file is still busy
                    is CancellationException -> throw t
                    else -> {
                        logger.error { "Error while checking whether '${this@isBusy}' is still busy: '${t.message}'" }
                        throw t
                    }
                }
            }
        )

    companion object {
        const val EXTENSION_XML = "xml"
        const val DEFAULT_INITIAL_DELAY_MILLIS = 2_000L
        const val DEFAULT_RESTART_DELAY_MILLIS = 15_000L
        const val DEFAULT_RESTART_DELAY_SECONDS = DEFAULT_RESTART_DELAY_MILLIS / 1_000L
    }

}
