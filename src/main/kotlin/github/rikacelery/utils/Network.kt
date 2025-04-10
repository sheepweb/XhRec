package github.rikacelery.utils

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*

suspend fun HttpClient.fetchContentLength(segmentUrl: String): Long {
    val resp = head(segmentUrl)
    return resp.headers[HttpHeaders.ContentLength]!!.toLong()
}