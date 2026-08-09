package dev.pschmitt.nyetbox.data.repository

import java.io.File
import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// NBC-434: downloadOrRevalidate is the Context-free core of
// FileDownloadRepository.downloadToPersistent, extracted specifically so this can run against a
// real MockWebServer without needing an Android Context (this project has no Robolectric).
//
// Revalidation is HEAD-first (see isUnchangedByHead): a HEAD carries no body either way, so it
// covers both a server that honors If-Modified-Since (304 on the HEAD itself) and one that doesn't
// but still reports an accurate Content-Length (compared against the cached file's size) - the
// latter is what a real deployment (netbox.brkn.lol) turned out to need, confirmed live.
class FileDownloadRevalidationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient()
        tempDir = Files.createTempDirectory("file-download-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `no existing file always downloads regardless of revalidate`() {
        server.enqueue(MockResponse().setBody("hello"))
        val target = File(tempDir, "target")

        val result =
            downloadOrRevalidate(client, server.url("/file").toString(), target, revalidate = false)

        assertEquals("hello", result.readText())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `existing file is trusted with zero requests when revalidate is false`() {
        val target = File(tempDir, "target").apply { writeText("cached") }

        val result =
            downloadOrRevalidate(client, server.url("/file").toString(), target, revalidate = false)

        assertEquals("cached", result.readText())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `revalidate keeps the file on a HEAD 304, without any GET`() {
        val target = File(tempDir, "target").apply { writeText("cached") }
        server.enqueue(MockResponse().setResponseCode(304))

        val result =
            downloadOrRevalidate(client, server.url("/file").toString(), target, revalidate = true)

        assertEquals("cached", result.readText())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertTrue(request.headers["If-Modified-Since"] != null)
    }

    @Test
    fun `revalidate skips the download when a non-304 HEAD still reports an unchanged Content-Length`() {
        val target = File(tempDir, "target").apply { writeText("cached") }
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", target.length())
        )

        val result =
            downloadOrRevalidate(client, server.url("/file").toString(), target, revalidate = true)

        assertEquals("cached", result.readText())
        assertEquals(1, server.requestCount)
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun `revalidate downloads fresh content when HEAD reports a different Content-Length`() {
        val target = File(tempDir, "target").apply { writeText("stale") }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", 999))
        server.enqueue(MockResponse().setBody("freshcontent"))

        val result =
            downloadOrRevalidate(client, server.url("/file").toString(), target, revalidate = true)

        assertEquals("freshcontent", result.readText())
        assertEquals(2, server.requestCount)
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `revalidate falls back to a full download when HEAD itself fails`() {
        val target = File(tempDir, "target").apply { writeText("cached") }
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setBody("freshcontent"))

        val result =
            downloadOrRevalidate(client, server.url("/file").toString(), target, revalidate = true)

        assertEquals("freshcontent", result.readText())
    }

    @Test(expected = IllegalStateException::class)
    fun `a failed download attempt after a changed HEAD throws without corrupting the existing file`() {
        val target = File(tempDir, "target").apply { writeText("cached") }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", 999))
        server.enqueue(MockResponse().setResponseCode(500))

        downloadOrRevalidate(client, server.url("/file").toString(), target, revalidate = true)
    }
}
