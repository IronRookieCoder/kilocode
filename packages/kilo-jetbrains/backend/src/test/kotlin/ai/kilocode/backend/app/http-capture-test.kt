package ai.kilocode.backend.app

import ai.kilocode.stability.Fixture
import ai.kilocode.stability.HttpFailure
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink

class HttpCaptureTest {
    companion object {
        private const val LIMIT = 64 * 1024
        private const val TIMEOUT = 10L
    }

    @Test
    fun `successful headers return before the server releases the response body`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val worker = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = TimeUnit.SECONDS.toMillis(TIMEOUT).toInt()
                    socket.getInputStream().bufferedReader().lineSequence().takeWhile { it.isNotEmpty() }.toList()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    check(release.await(TIMEOUT, TimeUnit.SECONDS)) { "Client did not accept headers before body" }
                    output.write("tail".toByteArray())
                    output.flush()
                }
            }
            val http = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()
            try {
                HttpCapture().client(http).newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                    .execute().use { response ->
                        assertEquals(200, response.code)
                        release.countDown()
                    }
                worker.get(TIMEOUT, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                executor.shutdownNow()
                http.dispatcher.executorService.shutdownNow()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `one shot request is written once while its snapshot stays bounded`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502).setBody("failure"))
            val writes = AtomicInteger()
            val content = "w".repeat(LIMIT * 2)
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun isOneShot() = true
                override fun writeTo(sink: okio.BufferedSink) {
                    check(writes.incrementAndGet() == 1) { "One-shot body was replayed" }
                    sink.writeUtf8(content)
                }
            }
            val http = OkHttpClient()
            try {
                val request = Request.Builder().url(server.url("/write")).post(body).build()
                val capture = HttpCapture(request)
                capture.client(http).newCall(request).execute().use { it.body!!.string() }
                assertEquals(content, server.takeRequest().body.readUtf8())
                assertEquals(1, writes.get())
                Fixture().use { fixture ->
                    fixture.enableDiagnostics()
                    fixture.operations.report(capture.input("http.write", HttpFailure(502, "failure")))
                    fixture.flush()
                    assertEquals(LIMIT, fixture.payload("request").length)
                }
            } finally {
                http.dispatcher.executorService.shutdownNow()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `response capture and gzip expansion are bounded without truncating caller reads`() {
        listOf(false, true).forEach { gzip ->
            MockWebServer().use { server ->
                val body = "x".repeat(LIMIT * 3)
                val bytes = Buffer()
                if (gzip) GzipSink(bytes).use { it.write(Buffer().writeUtf8(body), body.length.toLong()) }
                else bytes.writeUtf8(body)
                server.enqueue(MockResponse().setResponseCode(502).setBody(bytes)
                    .apply { if (gzip) setHeader("Content-Encoding", "gzip") })
                val http = OkHttpClient()
                try {
                    val capture = HttpCapture()
                    capture.client(http).newCall(Request.Builder().url(server.url("/failure")).build())
                        .execute().use { assertEquals(body, it.body!!.string()) }
                    Fixture().use { fixture ->
                        fixture.enableDiagnostics()
                        fixture.operations.report(capture.input("http.test", HttpFailure(502, "failure")))
                        fixture.flush()
                        assertEquals(LIMIT, fixture.payload("response").length, "gzip=$gzip exceeded capture bound or lost response")
                    }
                } finally {
                    http.dispatcher.executorService.shutdownNow()
                    http.connectionPool.evictAll()
                }
            }
        }
    }
}
