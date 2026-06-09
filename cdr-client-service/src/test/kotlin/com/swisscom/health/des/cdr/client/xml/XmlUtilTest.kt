package com.swisscom.health.des.cdr.client.xml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.xml.sax.SAXParseException

internal class XmlUtilTest {

    @ParameterizedTest
    @CsvSource(
        quoteCharacter = '"', textBlock =
            """
"/messages/generalInvoice450_qr_dt.xml", "http://www.forum-datenaustausch.ch/invoice"
"/messages/generalInvoiceResponse450_TarPSY_esrred.xml", "http://www.forum-datenaustausch.ch/invoice"
"/messages/notification_example_with_attachment.xml", "http://www.forum-datenaustausch.ch/notification"
"""
    )
    fun `test find schema definition`(invoiceFile: String, uri: String) {
        this::class.java.getResourceAsStream(invoiceFile)?.use {
            it.toDom().let { dom ->
                val schema = dom.fdDocType
                assertEquals(DocumentType.fromUri(uri), schema)
            }
        }
    }

    @Test
    fun `test fail finding schema definition`() {
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><hello/>".byteInputStream().use {
            it.toDom().let { dom ->
                val findSchemaDefinition = dom.fdDocType
                assertEquals(DocumentType.UNDEFINED, findSchemaDefinition)
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
