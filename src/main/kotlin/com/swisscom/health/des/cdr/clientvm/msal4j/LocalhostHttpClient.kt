package com.swisscom.health.des.cdr.clientvm.msal4j

import com.microsoft.aad.msal4j.HttpMethod
import com.microsoft.aad.msal4j.HttpRequest
import com.microsoft.aad.msal4j.HttpResponse
import com.microsoft.aad.msal4j.IHttpClient
import com.microsoft.aad.msal4j.IHttpResponse
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Scanner
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LocalhostHttpClient : IHttpClient {

    override fun send(httpRequest: HttpRequest?): IHttpResponse? {
        val originalUrl = httpRequest?.url()
        val modifiedUrl = if (httpRequest?.httpMethod() == HttpMethod.GET) {
            URL(originalUrl?.protocol, "localhost", WIRE_MOCK_PORT, originalUrl?.file)
        } else if (httpRequest?.httpMethod() == HttpMethod.POST) {
            URL(originalUrl?.protocol, originalUrl?.host, originalUrl?.port ?: MOCK_OAUTH_PORT, "/mock-issuer/token")
        } else {
            originalUrl
        }

        trustAll()

        val openConnection = modifiedUrl?.openConnection() as HttpURLConnection
        configureAdditionalHeaders(openConnection, httpRequest!!)
        return if (httpRequest.httpMethod() == HttpMethod.GET) {
            executeHttpGet(openConnection)
        } else if (httpRequest.httpMethod() == HttpMethod.POST) {
            executeHttpPost(openConnection, httpRequest)
        } else {
            null
        }
    }

    private fun trustAll() {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            @Suppress("EmptyFunctionBlock")
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            }

            @Suppress("EmptyFunctionBlock")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }

    @Throws(Exception::class)
    private fun executeHttpGet(conn: HttpURLConnection): HttpResponse {
        return readResponseFromConnection(conn)
    }

    @Throws(Exception::class)
    private fun executeHttpPost(conn: HttpURLConnection, httpRequest: HttpRequest): HttpResponse {
        conn.requestMethod = "POST"
        conn.doOutput = true

        var wr: DataOutputStream? = null
        try {
            wr = DataOutputStream(conn.outputStream)
            wr.writeBytes(httpRequest.body())
            wr.flush()

            return readResponseFromConnection(conn)
        } finally {
            wr?.close()
        }
    }

    // Copy from com.microsoft.aad.msal4j.DefaultHttpClient
    private fun configureAdditionalHeaders(conn: HttpURLConnection, httpRequest: HttpRequest) {
        if (httpRequest.headers() != null) {
            for ((key, value) in httpRequest.headers()) {
                if (value != null) {
                    conn.addRequestProperty(key, value)
                }
            }
        }
    }

    // Copy from com.microsoft.aad.msal4j.DefaultHttpClient
    private fun inputStreamToString(inputStream: InputStream): String {
        val s = Scanner(inputStream, StandardCharsets.UTF_8.name()).useDelimiter("\\A")
        return if (s.hasNext()) s.next() else ""
    }

    // Copy from com.microsoft.aad.msal4j.DefaultHttpClient
    @Throws(IOException::class)
    private fun readResponseFromConnection(conn: HttpURLConnection): HttpResponse {
        var inputStream: InputStream? = null
        try {
            val httpResponse = HttpResponse()
            val responseCode = conn.responseCode
            httpResponse.statusCode(responseCode)
            if (responseCode != HttpURLConnection.HTTP_OK) {
                inputStream = conn.errorStream
                if (inputStream != null) {
                    httpResponse.addHeaders(conn.headerFields)
                    httpResponse.body(inputStreamToString(inputStream))
                }
                return httpResponse
            }

            inputStream = conn.inputStream
            httpResponse.addHeaders(conn.headerFields)
            httpResponse.body(inputStreamToString(inputStream))
            return httpResponse
        } finally {
            inputStream?.close()
        }
    }

    companion object {
        const val WIRE_MOCK_PORT = 8443
        const val MOCK_OAUTH_PORT = 8444
    }
}
