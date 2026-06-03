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
        DOC_TYPE_ERROR_DIRECTORY,
        ARCHIVE_DIRECTORY,
        DOC_TYPE_ARCHIVE_DIRECTORY,
        IDP_CLIENT_PASSWORD,
        IDP_CLIENT_ID,
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
        PRODUCTION_INTERNAL("https", WELL_KNOWN_HTTPS_PORT, "cdr-prod-fn.azurewebsites.net"),
        STAGING("https", WELL_KNOWN_HTTPS_PORT, "stg.cdr.health.swisscom.ch"),
        STAGING_INTERNAL("https", WELL_KNOWN_HTTPS_PORT, "cdr-stg-fn.azurewebsites.net"),
        INTEGRATION_INTERNAL("https", WELL_KNOWN_HTTPS_PORT, "cdr-int-fn.azurewebsites.net"),
        // apparently, on MS Windows, it is possible for non-admin users to run processes that listen on port 80;
        // as non-admin users may call the cdr-client-service API and set the CDR API endpoint to `LOCALHOST` we
        // cannot allow a local endpoint that the same non-admin user may be able to control
        // https://stackoverflow.com/questions/169904/can-i-listen-on-a-port-using-httplistener-or-other-net-code-on-vista-without/6663823#6663823
        LOCALHOST("http", PLAINTEXT_API_PORT, "localhost"),
        UNKNOWN("",0,"");

        companion object {
            fun fromEndpointParts(protocol: String, port: Int, host: String): ApiEndpoint =
                entries.firstOrNull {
                    it.protocol == protocol && it.host == host && it.port == port
                } ?: UNKNOWN
        }
    }

    enum class TenantId(val tenantId: String) {
        PRODUCTION("70b434db-eccb-4280-95dc-1e59220aca55"),
        STAGING("dc5f3d15-71a0-47ad-a792-f708c9b4d123"),
        INTEGRATION("f5a99f8d-dca6-413c-ba36-9e23b4720930"),
        LOCALHOST("test-tenant-client-id"),
        UNKNOWN("");

        companion object {
            fun fromTenantId(tenantId: String): TenantId =
                entries.firstOrNull { it.tenantId == tenantId } ?: UNKNOWN
        }
    }

    enum class OAuthScope(val scope: String) {
        PRODUCTION("https://identity.health.swisscom.ch/CdrApi/.default"),
        STAGING("https://tst.identity.health.swisscom.ch/CdrApi/.default"),
        INTEGRATION("https://dev.identity.health.swisscom.ch/CdrApi/.default"),
        LOCALHOST("https://localhost/CdrApi/.default"),
        UNKNOWN("");

        companion object {
            fun fromScope(scope: String): OAuthScope =
                entries.firstOrNull { it.scope == scope } ?: UNKNOWN
        }
    }

    private companion object {
        private const val WELL_KNOWN_HTTPS_PORT = 443
        private const val PLAINTEXT_API_PORT = 87
    }

}
