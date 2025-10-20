package github.rikacelery.utils

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import okhttp3.ConnectionPool
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object ClientManager {
    private val logger = LoggerFactory.getLogger(ClientManager::class.java)
    private fun clientDirect(key: String): HttpClient {
        val pool = ConnectionPool(16, 5, TimeUnit.MINUTES)
        logger.info("create direct client key={}", key)
        return HttpClient(OkHttp) {
            configureClient()
            engine {
                config {
                    connectionPool(pool)
                    followSslRedirects(true)
                    followRedirects(true)
                }
            }
        }
    }

    private fun clientProxied(key: String): HttpClient {
        val pool = ConnectionPool(16, 5, TimeUnit.MINUTES)
        val proxyEnv = System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")
        logger.info("create proxied client key={} proxy={}", key, proxyEnv)
        return HttpClient(OkHttp) {
            configureClient()
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
            }
            engine {
                if (proxyEnv != null) {
                    val url = Url(proxyEnv)
                    proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(url.host, url.port))
                }
                config {
                    connectionPool(pool)
                    followSslRedirects(true)
                    followRedirects(true)
                }
            }
        }
    }

    private val clientsProxied = HashMap<String, HttpClient>()
    private val clientsDirect = HashMap<String, HttpClient>()
    private val lock = Any()

    private fun HttpClientConfig<OkHttpConfig>.configureClient() {
        expectSuccess = true
        install(HttpRequestRetry) {
            retryOnException(maxRetries = 3, retryOnTimeout = true)
            constantDelay(300)
        }
        install(DefaultRequest.Plugin) {
            headers {
                append(
                    HttpHeaders.Accept,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                )
                append(HttpHeaders.AcceptLanguage, "en,zh-CN;q=0.9,zh;q=0.8")
                append(HttpHeaders.Connection, "keep-alive")
            }
        }
    }

    fun getClient(key: String): HttpClient {
        synchronized(lock) {
            return clientsDirect[key] ?: clientDirect(key).also { clientsDirect[key] = it }
        }
    }

    fun getProxiedClient(key: String): HttpClient {
        synchronized(lock) {
            return clientsProxied[key] ?: clientProxied(key).also { clientsProxied[key] = it }
        }
    }
    fun close() {
        clientsProxied.forEach {
            it.value.close()
        }
        clientsDirect.forEach {
            it.value.close()
        }
    }
}