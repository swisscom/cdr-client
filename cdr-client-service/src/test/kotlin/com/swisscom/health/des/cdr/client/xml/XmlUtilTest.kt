package com.swisscom.health.des.cdr.client.xml

import com.swisscom.health.des.cdr.client.common.DocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.xml.sax.SAXParseException
import kotlin.io.path.toPath

internal class XmlUtilTest {

    @ParameterizedTest
    @CsvSource(
        quoteCharacter = '"', textBlock =
            """
"/messages/generalInvoice450_qr_dt.xml", "INVOICE", "REQUEST"
"/messages/generalInvoiceResponse450_TarPSY_esrred.xml", "INVOICE", "RESPONSE"
"/messages/notification_example_with_attachment.xml", "NOTIFICATION", "REQUEST"
"""
    )
    fun `test find schema definition`(invoiceFile: String, docType: String, commType: String) {
        requireNotNull(this::class.java.getResourceAsStream(invoiceFile)).use {
            it.toDom().let { dom ->
                val docTypeFromDom = dom.fdDocType
                val commTypeFromDom = dom.communicationType
                assertEquals(DocumentType.valueOf(docType), docTypeFromDom)
                assertEquals(CommunicationType.valueOf(commType), commTypeFromDom)
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        quoteCharacter = '"', textBlock =
            """
"/messages/dummy.xml"
"/messages/not_xml_at_all.xml"
"""
    )
    fun `test extractDocumentMetaData does not throw`(notForumDatenaustauschFile: String) {
        requireNotNull(this::class.java.getResource(notForumDatenaustauschFile)).let { url ->
            url.toURI().toPath().let { path ->
                val docMeta = assertDoesNotThrow { path.extractDocumentMetaData() }
                assertEquals(DocumentMetaData.UNKNOWN, docMeta)
            }
        }
    }

    @Test
    fun `test fail finding schema definition`() {
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><hello/>".byteInputStream().use {
            it.toDom().let { dom ->
                val findSchemaDefinition = dom.fdDocType
                assertEquals(DocumentType.UNKNOWN, findSchemaDefinition)
            }
        }
    }

    @Test
    fun `test fail finding schema definition for non xml file`() {
        "Text file".byteInputStream().use {
            assertThrows<SAXParseException> { it.toDom() }
        }
    }

}
