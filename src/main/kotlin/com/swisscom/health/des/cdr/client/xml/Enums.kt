package com.swisscom.health.des.cdr.client.xml

import com.fasterxml.jackson.annotation.JsonCreator

/**
 * Forum Datenaustausch namespaces with their "canonical" prefixes.
 *
 * @param uri The namespace URI.
 * @param prefix The 'canonical' prefix of the namespace.
 */
enum class ForumDatenaustauschNamespaces(val uri: String, val prefix: String) {
    UNDEFINED("UNDEFINED", MessageType.UNDEFINED.prefix),
    CONTAINER("http://www.forum-datenaustausch.ch/container", MessageType.CONTAINER.prefix),
    CREDIT("http://sumex1.net/gcr generalCreditRequest", MessageType.CREDIT.prefix),
    FORM("http://www.forum-datenaustausch.ch/form", MessageType.FORM.prefix),
    HOSPITAL_MCD("http://www.forum-datenaustausch.ch/mcd", MessageType.HOSPITAL_MCD.prefix),
    INVOICE("http://www.forum-datenaustausch.ch/invoice", MessageType.INVOICE.prefix),
    NOTIFICATION("http://www.forum-datenaustausch.ch/notification", MessageType.NOTIFICATION.prefix);

    companion object {
        /**
         * Find a Forum Datenaustausch namespace by its URI. Returns `null` if no namespace with the given URI exists.
         *
         * @param uri The namespace URI.
         * @return The Forum Datenaustausch namespace with the given URI or `null` if no such namespace exists.
         */
        fun fromUri(uri: String?): ForumDatenaustauschNamespaces? {
            return entries.firstOrNull { it.uri == uri }
        }
    }

}

enum class MessageType(val prefix: String) {
    CONTAINER("container"),
    CREDIT("gcr"),
    FORM("form"),
    HOSPITAL_MCD("mcd"),
    INVOICE("invoice"),
    NOTIFICATION("notification"),
    UNDEFINED("");

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromValue(value: String): MessageType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: UNDEFINED
        }
    }
}
