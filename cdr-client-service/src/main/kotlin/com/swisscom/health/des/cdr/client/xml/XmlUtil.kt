package com.swisscom.health.des.cdr.client.xml

import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Document
import java.io.InputStream
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream

private val logger = KotlinLogging.logger {}

internal fun Path.extractDocumentType(): DocumentType = runCatching {
    // TODO: use StAX parser instead of DOM parser to avoid loading the entire file into memory just to determine the document type
    this@extractDocumentType.inputStream().use { it.toDom().fdDocType }
}.fold(
    onSuccess = { it },
    onFailure = { e ->
        logger.error { "Failed to determine document type of file '$this': ${e.message}" }
        DocumentType.UNDEFINED
    }
)


internal val Document.fdDocType: DocumentType
    get() {
        fun isEven(value: Int) = value % 2 == 0
        val namespaceDeclarationElements = this.documentElement.attributes
            .getNamedItemNS("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation")
            // attribute node value is a string, but might be null
            ?.nodeValue
            // required so the `splitToSequence(..)` does not produce a first/last sequence element consisting of a single whitespace character
            ?.trim()
            // create sequence of strings separated by any whitespace
            ?.splitToSequence("""\s+""".toRegex())
            // concatenate element n with n+1
            ?.zipWithNext { namespace, schemaLocation -> "$namespace $schemaLocation" }
            // zipping does not skip the n+1 element, which results in undesired pairs (in our case) and we need to filter out every other zip result
            ?.filterIndexed { index, _ -> isEven(index) }
            ?.toSet()
            ?: emptySet()

        val find = DocumentType.entries.find { pc ->
            namespaceDeclarationElements.any { namespace -> namespace.startsWith(pc.uri) }
        }

        return find ?: DocumentType.UNDEFINED
    }

internal fun InputStream.toDom(): Document =
    DOCUMENT_BUILDER_FACTORY.newDocumentBuilder().parse(this)

private val DOCUMENT_BUILDER_FACTORY: DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // disallow DTDs
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        // instructs XML processors, such as parsers, validators and transformers, to try and process XML securely.
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        // disable external DTDs
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        // per Timothy Morgan's 2014 paper: "XML Schema, DTD, and Entity Attacks"
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
