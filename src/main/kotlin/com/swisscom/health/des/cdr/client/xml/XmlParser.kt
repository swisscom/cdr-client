package com.swisscom.health.des.cdr.client.xml

import org.springframework.stereotype.Service
import org.w3c.dom.Document
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@Service
class XmlParser {

    fun findSchemaDefinition(doc: Document): ForumDatenaustauschNamespaces {
        fun isEven(value: Int) = value % 2 == 0
        val namespaceDeclarationElements = doc.documentElement.attributes
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

        val find = ForumDatenaustauschNamespaces.entries.find { pc ->
            namespaceDeclarationElements.any{ namespace -> namespace.startsWith(pc.uri) }
        }

        return find ?: ForumDatenaustauschNamespaces.UNDEFINED
    }

}

private fun documentBuilderFactory(): DocumentBuilderFactory =
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

internal fun InputStream.toDom(): Document =
    documentBuilderFactory().newDocumentBuilder().parse(this)
