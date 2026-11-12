// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.content.Context
import com.shihab.diplay.host.R
import com.shihab.diplay.util.AppLog
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import java.io.IOException
import java.net.Inet4Address
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal object BingOriginal

internal class OkHttpWallpaperTransport(context: Context) : WallpaperTransport {
    private val app = context.applicationContext
    private val agent = userAgent(app)
    private val calls = CopyOnWriteArrayList<Call>()
    private val client: OkHttpClient by lazy { buildClient(app) }

    override fun get(url: String): WallpaperHttpResult = get(url, false)

    override fun get(url: String, bingOriginal: Boolean): WallpaperHttpResult {
        if (!WallpaperHosts.allows(url, bingOriginal)) return WallpaperHttpResult(0, ByteArray(0), url, false)
        val request = Request.Builder().url(url).header("User-Agent", agent).apply {
            if (bingOriginal) tag(BingOriginal)
        }.build()
        val call = client.newCall(request)
        calls.add(call)
        return try {
            call.execute().use { response ->
                val finalUrl = response.request().url().toString()
                val payload = response.body()
                if (payload == null) {
                    WallpaperHttpResult(response.code(), ByteArray(0), finalUrl, false)
                } else {
                    val length = payload.contentLength()
                    val bytes = if (length > WallpaperClient.MAX_BODY) {
                        null
                    } else {
                        WallpaperBitmaps.readCapped(payload.byteStream(), WallpaperClient.MAX_BODY)
                    }
                    if (bytes == null) WallpaperHttpResult(response.code(), ByteArray(0), finalUrl, false)
                    else WallpaperHttpResult(response.code(), bytes, finalUrl, response.isSuccessful)
                }
            }
        } catch (error: Exception) {
            AppLog.w("Wallpaper", "request failed", error)
            WallpaperHttpResult(0, ByteArray(0), url, false)
        } finally {
            calls.remove(call)
        }
    }

    fun cancel() {
        calls.forEach { it.cancel() }
    }

    companion object {
        fun userAgent(context: Context): String {
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (error: Exception) {
                null
            } ?: "0"
            return "75Play/$version (wallpaper)"
        }

        private fun buildClient(context: Context): OkHttpClient {
            val trust = compositeTrust(context)
            val factory = SSLContext.getInstance("TLSv1.2", org.conscrypt.Conscrypt.newProvider())
                .apply { init(null, arrayOf(trust), null) }
                .socketFactory
            val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
            return OkHttpClient.Builder()
                .sslSocketFactory(factory, trust)
                .connectionSpecs(listOf(spec))
                .protocols(listOf(Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .dns { hostname ->
                    Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
                }
                .addNetworkInterceptor { chain ->
                    val next = chain.request().url().toString()
                    val original = chain.request().tag() is BingOriginal
                    if (!WallpaperHosts.allows(next, original)) throw IOException("wallpaper host refused")
                    chain.proceed(chain.request())
                }
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private fun compositeTrust(context: Context): X509TrustManager {
            val systemFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            systemFactory.init(null as KeyStore?)
            val system = systemFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val bundled = bundledTrust(context)
            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    accept(chain, authType, X509TrustManager::checkClientTrusted)
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    accept(chain, authType, X509TrustManager::checkServerTrusted)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    (system.acceptedIssuers + bundled.acceptedIssuers)
                private fun accept(
                    chain: Array<X509Certificate>,
                    authType: String,
                    check: X509TrustManager.(Array<X509Certificate>, String) -> Unit,
                ) {
                    try {
                        system.check(chain, authType)
                    } catch (systemError: Exception) {
                        try {
                            bundled.check(chain, authType)
                        } catch (bundledError: Exception) {
                            throw CertificateException(systemError)
                        }
                    }
                }
            }
        }

        private fun bundledTrust(context: Context): X509TrustManager {
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            context.resources.openRawResource(R.raw.update_ca_bundle).use { stream ->
                val pem = stream.readBytes().toString(Charsets.UTF_8)
                pem.split("-----BEGIN CERTIFICATE-----").drop(1).forEachIndexed { index, block ->
                    val end = block.indexOf("-----END CERTIFICATE-----")
                    if (end < 0) return@forEachIndexed
                    val certPem = "-----BEGIN CERTIFICATE-----" + block.substring(0, end) +
                        "-----END CERTIFICATE-----\n"
                    try {
                        store.setCertificateEntry("wallpaper-root-$index", factory.generateCertificate(certPem.byteInputStream()))
                    } catch (error: Exception) {
                        AppLog.w("Wallpaper", "wallpaper CA certificate parse failed", error)
                    }
                }
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(store)
            return trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}
