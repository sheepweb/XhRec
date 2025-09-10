package github.rikacelery.utils

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import okhttp3.ConnectionPool
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object ClientManager {
    private val CONNECTION_POOL = ConnectionPool(64, 5, TimeUnit.MINUTES)

    fun getClient(): HttpClient {
        return HttpClient(OkHttp) {
            configureClient()
            engine {
                config {
                    connectionPool(CONNECTION_POOL)
                    followSslRedirects(true)
                    followRedirects(true)
                }
            }
        }
    }
    fun getProxiedClient(): HttpClient {
        return HttpClient(OkHttp) {
            configureClient()
            engine {
                val proxyEnv = System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")
                if (proxyEnv != null) {
                    println("Using http proxy $proxyEnv")
                    val url = Url(proxyEnv)
                    proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(url.host, url.port))
                }
                config {
                    connectionPool(CONNECTION_POOL)
                    followSslRedirects(true)
                    followRedirects(true)
                }
            }
        }
    }

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
    fun close(){
        CONNECTION_POOL.evictAll()
    }
}