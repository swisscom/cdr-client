@file:Suppress("TooManyFunctions")

package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.Constants.ARCHIVE_DIR_NAME
import com.swisscom.health.des.cdr.client.common.Constants.ERROR_DIR_NAME
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.config.Connector.DocTypeFolders
import com.swisscom.health.des.cdr.client.xml.CommunicationType
import com.swisscom.health.des.cdr.client.xml.DocumentMetaData
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory

private val logger = KotlinLogging.logger {}

/**
 * Returns an ISO-8601 basic format date string representing the current date, e.g. "20260611".
 */
private fun getDateNow(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

/**
 * Based on the path, which can be either a file path or a directory path, returns the first
 * connector found that has that path (parent directory in the case of a file path) configured as
 * a source path.
 *
 * As the file by definition must have been read from a configured source path, and because we
 * validate that source paths do not overlap between connectors, the following should always be
 * true:
 * * the returned connector is the only connector (and not only the first one) associated with
 *   that path
 * * it is guaranteed that a connector can be found
 *
 * @param file the file or directory path to look up
 * @param docMetaData document and communication type of [file] to narrow down the paths to be
 *   searched
 * @return The matching [Connector]
 *
 * @throws NoSuchElementException if no connector with a matching source path can be found
 */
internal fun List<Connector>.getConnectorBySourceFolder(file: Path, docMetaData: DocumentMetaData): Connector =
    when (file.isDirectory()) {
        true -> file
        false -> file.parent
    }.let { dir: Path ->
        this.first { it.getEffectiveSourceFolders(docMetaData.documentType).contains(dir) }
    }

/**
 * The document type specific directory configurations defined in the connector configuration are optional;
 * if they are not set, the effective directory configuration for a document type falls back to an empty map.
 */
internal val Connector.effectiveDocTypeFolders: Map<DocumentType, DocTypeFolders>
    get() = this.docTypeFolders ?: emptyMap()

/**
 * Computed property containing all source directories of the connector, taking into account all optional source
 * directory configurations per document type and whether the document type is configured with separate
 * directories for request and response documents.
 *
 * The returned map contains each [DocumentType] as key with the effective source directories (could be multiple
 * due to request/response split) of that document type as value. If the only source directory configured is the
 * base source directory, then all document types will be associated with the base directory.
 *
 * To obtain the list of unique source directories for the connector dedupe the values of the returned map:
 * `connector.effectiveSourceFolders.values.flatten().distinct()`
 */
internal val Connector.effectiveSourceFolders: Map<DocumentType, List<Path>>
    get() = DocumentType.entries.associateWith { getEffectiveSourceFolders(it) }

/**
 * Computes the list of source directories of the connector, narrowed down to the supplied document type. The
 * returned list of paths is the same list as if calling `connector.effectiveSourceFolders[docType]`.
 *
 * @param docType The document type to compute the effective source directories for
 * @return the list of effective source directories for the supplied document type
 * @see effectiveSourceFolders
 */
internal fun Connector.getEffectiveSourceFolders(docType: DocumentType): List<Path> =
    if (effectiveDocTypeFolders[docType]?.requestResponseSplit == true) {
        CommunicationType.entries
            .filter { it != CommunicationType.UNKNOWN }
            .map { getEffectiveSourceFolder(DocumentMetaData(docType, it)) }
    } else {
        listOf(getEffectiveSourceFolderNoRequestResponseSplit(docType))
    }

/**
 * Computes the source directory for the given document type and request/response communication type. If no
 * source directory is configured for either the base source directory is returned.
 *
 * @param docMeta The document type and communication type to filter for.
 * @return The computed target directory
 * @see effectiveSourceFolders
 */
internal fun Connector.getEffectiveSourceFolder(docMeta: DocumentMetaData): Path =
    if (effectiveDocTypeFolders[docMeta.documentType]?.requestResponseSplit == true) {
        when (docMeta.communicationType) {
            CommunicationType.REQUEST, CommunicationType.UNKNOWN -> effectiveDocTypeFolders[docMeta.documentType]?.sourceFolderReq
            CommunicationType.RESPONSE -> effectiveDocTypeFolders[docMeta.documentType]?.sourceFolderResp
        } ?: run {
            logger.warn {
                "Connector '$connectorId' has request/response split active for document type '${docMeta.documentType}', but no source directory " +
                        "for communication type '${docMeta.communicationType}' is defined; falling back to no-split logic to determine a source directory."
            }
            getEffectiveSourceFolderNoRequestResponseSplit(docMeta.documentType)
        }
    } else {
        getEffectiveSourceFolderNoRequestResponseSplit(docMeta.documentType)
    }

private fun Connector.getEffectiveSourceFolderNoRequestResponseSplit(docType: DocumentType): Path =
    effectiveDocTypeFolders[docType]?.sourceFolder?.let { docTypeSourceFolder ->
        when (docTypeSourceFolder.isAbsolute) {
            true -> docTypeSourceFolder
            else -> sourceFolder.resolve(docTypeSourceFolder)
        }
    } ?: sourceFolder


/**
 * Computed property containing all target directories of the connector, taking into account all optional source
 * directory configurations per document type and whether the document type is configured with separate
 * directories for request and response documents.
 *
 * The returned map contains each [DocumentType] as key with the effective target directories (could be multiple
 * due to request/response split) of that document type as value. If the only target directory configured is the
 * base target directory, then all document types will be associated with the base directory.
 *
 * To obtain the list of unique target directories for the connector dedupe the values of the returned map:
 * `connector.effectiveTargetFolders.values.flatten().distinct()`
 */
internal val Connector.effectiveTargetFolders: Map<DocumentType, List<Path>>
    get() = DocumentType.entries.associateWith { getEffectiveTargetFolders(it) }

/**
 * Computes the list of target directories of the connector, narrowed down to the supplied document type. The
 * returned list of paths is the same list as if calling `connector.effectiveTargetFolders[docType]`.
 *
 * @param docType The document type to compute the effective target directories for
 * @return the list of effective target directories for the supplied document type
 * @see effectiveTargetFolders
 */
internal fun Connector.getEffectiveTargetFolders(docType: DocumentType): List<Path> =
    if (effectiveDocTypeFolders[docType]?.requestResponseSplit == true) {
        CommunicationType.entries
            .filter { it != CommunicationType.UNKNOWN }
            .map { getEffectiveTargetFolder(DocumentMetaData(docType, it)) }
    } else {
        listOf(getEffectiveTargetFolderNoRequestResponseSplit(docType))
    }

/**
 * Computes the target directory for the given document type and request/response communication type. If no
 * target directory is configured for either the base target directory is returned.
 *
 * @param docMeta The document type and communication type to filter for.
 * @return The computed target directory
 * @see effectiveTargetFolders
 */
internal fun Connector.getEffectiveTargetFolder(docMeta: DocumentMetaData): Path =
    if (effectiveDocTypeFolders[docMeta.documentType]?.requestResponseSplit == true) {
        when (docMeta.communicationType) {
            CommunicationType.REQUEST, CommunicationType.UNKNOWN -> effectiveDocTypeFolders[docMeta.documentType]?.targetFolderReq
            CommunicationType.RESPONSE -> effectiveDocTypeFolders[docMeta.documentType]?.targetFolderResp
        } ?: run {
            logger.warn {
                "Connector '$connectorId' has request/response split active for document type '${docMeta.documentType}', but no target directory " +
                        "for communication type '${docMeta.communicationType}' is defined; falling back to no-split logic to determine a target directory."
            }
            getEffectiveTargetFolderNoRequestResponseSplit(docMeta.documentType)
        }
    } else {
        getEffectiveTargetFolderNoRequestResponseSplit(docMeta.documentType)
    }

private fun Connector.getEffectiveTargetFolderNoRequestResponseSplit(docType: DocumentType): Path =
    effectiveDocTypeFolders[docType]?.targetFolder?.let { docTypeTargetFolder ->
        when (docTypeTargetFolder.isAbsolute) {
            true -> docTypeTargetFolder
            else -> targetFolder.resolve(docTypeTargetFolder)
        }
    } ?: targetFolder

/**
 * Computed property containing all archive directories of the connector, taking into account all optional archive
 * directory configurations per document type and whether the document type is configured with separate
 * directories for request and response documents.
 *
 * If [Connector.sourceArchiveEnabled] is set to `false`, then all lists in the map are empty.
 *
 * If [Connector.sourceArchiveEnabled] is set to `true`, then the returned map contains each [DocumentType] as key
 * with the effective archive directories (could be multiple due to request/response split) of that document type
 * as value. If request-response splitting is disabled and if the only archive directory configured is the base
 * target directory, then all document types will be associated with the base directory. If request-response
 * splitting is enabled for a given document type, then a document type specific archive directory is mandatory
 * and that directory will be returned.
 *
 * To obtain the list of unique archive directories for the connector dedupe the values of the returned map:
 * `connector.effectiveArchiveFolders.values.flatten().distinct()`
 */
internal val Connector.effectiveArchiveFolders: Map<DocumentType, List<Path>>
    get() = DocumentType.entries.associateWith { docType ->
        if (sourceArchiveEnabled) {
            if (effectiveDocTypeFolders[docType]?.requestResponseSplit == true) {
                CommunicationType.entries
                    .mapNotNull { comType ->
                        getEffectiveSourceArchiveFolder(DocumentMetaData(docType, comType))
                    }
            } else {
                listOfNotNull(getEffectiveSourceArchiveFolderNoRequestResponseSplit(docType))
            }
        } else {
            emptyList()
        }
    }

/**
 * If...
 * * [sourceArchiveEnabled] is `true` and [DocTypeFolders.requestResponseSplit] is `false`, and
 *     * the doc type specific archive directory is a relative path, then it is resolved against the effective
 *     doc type specific source directory
 *     * the doc type specific archive directory is an absolute path, then it is returned as is
 *     * the doc type specific archive directory is not set, then the base archive directory is returned
 * * [sourceArchiveEnabled] is `true` and [DocTypeFolders.requestResponseSplit] is `true` then
 *     * the doc type specific archive directory must be an absolute path; the returned path is that
 *     archive directory with the [CommunicationType] name resolved as a subdirectory
 * * [sourceArchiveEnabled] is `false`, then
 *     * `null` is returned
 *
 * @see sourceArchiveEnabled
 * @see sourceArchiveFolder
 * @see getDailyEffectiveSourceArchiveFolder
 * @param docMeta The document type and communication type to filter for.
 * @return The potentially document type and communication type specific archive directory as an absolute path
 */
internal fun Connector.getEffectiveSourceArchiveFolder(docMeta: DocumentMetaData): Path? =
    if (sourceArchiveEnabled) {
        if (effectiveDocTypeFolders[docMeta.documentType]?.requestResponseSplit == true) {
            effectiveDocTypeFolders[docMeta.documentType]?.archiveFolder?.resolve(docMeta.communicationType.name)
                ?: run {
                    logger.warn {
                        "Connector '$connectorId' has request/response split active for document type '${docMeta.documentType}', but no archive " +
                                "directory for communication type '${docMeta.communicationType}' is defined; falling back to no-split logic to " +
                                "determine an archive directory."
                    }
                    getEffectiveSourceArchiveFolderNoRequestResponseSplit(docMeta.documentType)
                }
        } else {
            getEffectiveSourceArchiveFolderNoRequestResponseSplit(docMeta.documentType)
        }
    } else {
        null
    }

private fun Connector.getEffectiveSourceArchiveFolderNoRequestResponseSplit(docType: DocumentType): Path? =
    if (sourceArchiveEnabled) {
        when (val docTypeArchiveFolder = effectiveDocTypeFolders[docType]?.archiveFolder) {
            null -> {
                if (getEffectiveSourceFolderNoRequestResponseSplit(docType) == sourceFolder) {
                    baseSourceArchiveFolder // no doc type specific source dir -> use base archive dir
                } else {
                    getEffectiveSourceFolderNoRequestResponseSplit(docType).resolve(ARCHIVE_DIR_NAME) //
                }
            }

            else -> when (docTypeArchiveFolder.isAbsolute) {
                true -> docTypeArchiveFolder
                false -> getEffectiveSourceFolderNoRequestResponseSplit(docType).resolve(docTypeArchiveFolder)
            }
        } ?: baseSourceArchiveFolder
    } else {
        null
    }

/**
 * If [Connector.sourceArchiveEnabled] is set to `true`
 * * takes the result of [getEffectiveSourceArchiveFolder] and resolves the current date as a subdirectory and returns
 *   the resulting directory.
 *
 * else
 * * returns `null`
 *
 * @see getEffectiveSourceArchiveFolder
 * @param docMeta The document type and communication type to filter for.
 * @return The daily archive folder or `null` if archiving is disabled
 */
internal fun Connector.getDailyEffectiveSourceArchiveFolder(docMeta: DocumentMetaData): Path? =
    getEffectiveSourceArchiveFolder(docMeta)?.let { archiveRootFolder: Path ->
        runCatching {
            archiveRootFolder.resolve(getDateNow()).createDirectories()
        }.getOrElse { t ->
            logger.warn { "Failed to create daily archive folder ${archiveRootFolder.resolve(getDateNow())}: $t" }
            archiveRootFolder // fallback to non-daily folder if creation of daily folder fails
        }
    }

/**
 * The source archive directory as an absolute path. If archiving is disabled, then `null` is returned.
 */
internal val Connector.baseSourceArchiveFolder: Path?
    get() =
        if (sourceArchiveEnabled) {
            when (sourceArchiveFolder) {
                null -> sourceFolder.resolve(ARCHIVE_DIR_NAME)
                sourceFolder -> sourceFolder.resolve(ARCHIVE_DIR_NAME)
                // `Path::resolve` returns `other`, if `other` is an absolute path -> the `else` branch below covers the test
                // for an absolute archive path, that does not coincide with the absolute source path
                else -> sourceFolder.resolve(sourceArchiveFolder)
            }
        } else {
            null
        }

internal val Connector.effectiveErrorFolders: Map<DocumentType, List<Path>>
    get() = DocumentType.entries.associateWith { docType ->
        if (effectiveDocTypeFolders[docType]?.requestResponseSplit == true) {
            CommunicationType.entries
                .map { comType -> getEffectiveSourceErrorFolder(DocumentMetaData(docType, comType)) }
        } else {
            listOf(getEffectiveSourceErrorFolderNoRequestResponseSplit(docType))
        }
    }

/**
 * Returns the effective source error directory.
 *
 * If ...
 * * the doc type specific error directory is a relative path, then it is resolved against the doc type specific source directory;
 *   if the source directory is not set an exception is raised
 * * the doc type specific error directory is an absolute path, it is returned as is
 * * the doc type specific error directory is not set, then the base error directory is returned
 *
 * The effective error directory is returned as an absolute path.
 *
 * @see sourceErrorFolder
 * @see sourceFolder
 *
 * @return the computed, document type specific error directory as an absolute path
 */
internal fun Connector.getEffectiveSourceErrorFolder(docMeta: DocumentMetaData): Path =
    if (effectiveDocTypeFolders[docMeta.documentType]?.requestResponseSplit == true) {
        effectiveDocTypeFolders[docMeta.documentType]?.errorFolder?.resolve(docMeta.communicationType.name)
            ?: run {
                logger.warn {
                    "Connector '$connectorId' has request/response split active for document type '${docMeta.documentType}', but no error " +
                            "directory for communication type '${docMeta.communicationType}' is defined; falling back to no-split logic to " +
                            "determine an error directory."
                }
                getEffectiveSourceErrorFolderNoRequestResponseSplit(docMeta.documentType)
            }
    } else {
        getEffectiveSourceErrorFolderNoRequestResponseSplit(docMeta.documentType)
    }

private fun Connector.getEffectiveSourceErrorFolderNoRequestResponseSplit(docType: DocumentType): Path =
    when (val docTypeErrorFolder = effectiveDocTypeFolders[docType]?.errorFolder) {
        null -> {
            if (getEffectiveSourceFolderNoRequestResponseSplit(docType) == sourceFolder) {
                baseSourceErrorFolder // no doc type specific source dir -> use base error dir
            } else {
                getEffectiveSourceFolderNoRequestResponseSplit(docType).resolve(ERROR_DIR_NAME) //
            }
        }

        else -> when (docTypeErrorFolder.isAbsolute) {
            true -> docTypeErrorFolder
            false -> getEffectiveSourceFolderNoRequestResponseSplit(docType).resolve(docTypeErrorFolder)
        }
    }

/**
 * Returns the source error directory as an absolute path; if no explicit error directory config was set,
 * the default error directory is a subdirectory of [Connector.sourceFolder].
 */
internal val Connector.baseSourceErrorFolder: Path
    get() =
        when (sourceErrorFolder) {
            null -> sourceFolder.resolve(ERROR_DIR_NAME)
            sourceFolder -> sourceFolder.resolve(ERROR_DIR_NAME)
            // `Path::resolve` returns `other`, if `other` is an absolute path -> the `else` branch below covers the test
            // for an absolute error path, that does not coincide with the absolute source path
            else -> sourceFolder.resolve(sourceErrorFolder)
        }
