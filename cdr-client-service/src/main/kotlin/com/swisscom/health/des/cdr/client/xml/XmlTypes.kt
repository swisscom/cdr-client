package com.swisscom.health.des.cdr.client.xml

import com.fasterxml.jackson.annotation.JsonCreator
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING

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

/**
 * Forum Datenaustausch namespaces with their "canonical" prefixes.
 *
 * @param uri The namespace URI.
 * @param prefix The 'canonical' prefix of the namespace.
 */
internal enum class DocumentType(val uri: String, val prefix: String) {
    UNKNOWN("UNDEFINED", EMPTY_STRING),
    CONTAINER("http://www.forum-datenaustausch.ch/container", "container"),
    CREDIT("http://sumex1.net/gcr generalCreditRequest", "gcr"),
    FORM("http://www.forum-datenaustausch.ch/form", "form"),
    HOSPITAL_MCD("http://www.forum-datenaustausch.ch/mcd", "mcd"),
    INVOICE("http://www.forum-datenaustausch.ch/invoice", "invoice"),
    NOTIFICATION("http://www.forum-datenaustausch.ch/notification", "notification"),
    PUSH_ADMIN_MSG("http://www.forum-datenaustausch.ch/pam", "pam");

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromName(value: String): DocumentType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }

}
