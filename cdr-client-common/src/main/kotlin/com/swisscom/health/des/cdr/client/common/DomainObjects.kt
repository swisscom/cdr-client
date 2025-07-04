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
        TARGET_DIRECTORY,
        ERROR_DIRECTORY,
        ARCHIVE_DIRECTORY,
        IDP_CLIENT_PASSWORD,
        IDP_CLIENT_ID,
        IDP_TENANT_ID,
        IDP_CLIENT_SECRET_RENWAL_TIME,
        IDP_CLIENT_SECRET_RENWAL,
        CONNECTOR_MODE,
        FILE_BUSY_TEST_TIMEOUT,
        CONNECTOR,
        CONNECTOR_ID,
    }

    enum class ValidationType {
        DIR_READ_WRITABLE,
        DIR_SINGLE_USE,
    }

}
