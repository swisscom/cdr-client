package com.swisscom.health.des.cdr.client.xml

import com.swisscom.health.des.cdr.client.common.DocumentType

internal data class DocumentMetaData(
    val documentType: DocumentType,
    val communicationType: CommunicationType,
) {
    companion object {
        @JvmStatic
        val UNKNOWN = DocumentMetaData(DocumentType.UNKNOWN, CommunicationType.UNKNOWN)
    }
}

internal enum class CommunicationType {
    UNKNOWN,
    REQUEST,
    RESPONSE
}
