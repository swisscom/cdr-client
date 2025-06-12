package com.swisscom.health.des.cdr.client.xml

import com.fasterxml.jackson.annotation.JsonCreator

/**
 * Forum Datenaustausch namespaces with their "canonical" prefixes.
 *
 * @param uri The namespace URI.
 * @param prefix The 'canonical' prefix of the namespace.
 */
enum class DocumentType(val uri: String, val prefix: String) {
    UNDEFINED("UNDEFINED", ""),
    CONTAINER("http://www.forum-datenaustausch.ch/container", "container"),
    CREDIT("http://sumex1.net/gcr generalCreditRequest", "gcr"),
    FORM("http://www.forum-datenaustausch.ch/form", "form"),
    HOSPITAL_MCD("http://www.forum-datenaustausch.ch/mcd", "mcd"),
    INVOICE("http://www.forum-datenaustausch.ch/invoice", "invoice"),
    NOTIFICATION("http://www.forum-datenaustausch.ch/notification", "notification");

    companion object {
        /**
         * Find a Forum Datenaustausch namespace by its URI. Returns `null` if no namespace with the given URI exists.
         *
         * @param uri The namespace URI.
         * @return The Forum Datenaustausch namespace with the given URI or `null` if no such namespace exists.
         */
        fun fromUri(uri: String?): DocumentType? {
            return entries.firstOrNull { it.uri == uri }
        }

        @JsonCreator
        @JvmStatic
        fun fromName(value: String): DocumentType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true)} ?: UNDEFINED
        }
    }

}
