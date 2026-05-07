package com.swisscom.health.des.cdr.client.common


class DomainObjects {

    enum class ConfigurationItem {
        UNKNOWN,
        SYNC_SWITCH,
        ARCHIVE_SWITCH,
        LOCAL_DIRECTORY,
        CDR_API_HOST,
        FILE_BUSY_TEST_STRATEGY,
        SOURCE_DIRECTORY,
        DOC_TYPE_SOURCE_DIRECTORY,
        TARGET_DIRECTORY,
        DOC_TYPE_TARGET_DIRECTORY,
        ERROR_DIRECTORY,
        ARCHIVE_DIRECTORY,
        IDP_CLIENT_PASSWORD,
        IDP_CLIENT_ID,
        IDP_TENANT_ID,
        IDP_CLIENT_SECRET_RENWAL_TIME,
        IDP_CLIENT_SECRET_RENWAL,
        FILE_BUSY_TEST_TIMEOUT,
        CONNECTOR,
        CONNECTOR_MODE,
        CONNECTOR_ID,
        PROXY_URL,
        PROXY_USERNAME,
        PROXY_PASSWORD,
    }

    enum class ValidationType {
        DIR_READ_WRITABLE,
        DIR_SINGLE_USE,
        MODE_OVERLAP,
        MODE_VALUE,
    }

    enum class ApiEndpoint(val protocol: String, val port: Int, val host: String) {
        PRODUCTION("https", WELL_KNOWN_HTTPS_PORT, "cdr.health.swisscom.ch"),
        PROD_INTERNAL("https", WELL_KNOWN_HTTPS_PORT, "cdr-prod-fn.azurewebsites.net"),
        STAGING("https", WELL_KNOWN_HTTPS_PORT, "stg.cdr.health.swisscom.ch"),
        STAGING_INTERNAL("https", WELL_KNOWN_HTTPS_PORT, "cdr-stg-fn.azurewebsites.net"),
        INT_INTERNAL("https", WELL_KNOWN_HTTPS_PORT, "cdr-int-fn.azurewebsites.net"),
        LOCALHOST("http", WELL_KNOWN_HTTP_PORT, "localhost"),
        UNKNOWN("",0,"");

        companion object {
            fun fromEndpointParts(protocol: String, port: Int, host: String): ApiEndpoint =
                entries.firstOrNull {
                    it.protocol == protocol && it.host == host && it.port == port
                } ?: UNKNOWN

        }

    }

    private companion object {
        private const val WELL_KNOWN_HTTPS_PORT = 443
        private const val WELL_KNOWN_HTTP_PORT = 80
    }

}
