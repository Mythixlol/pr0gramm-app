package com.pr0gramm.app.ui.views.viewer.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.pr0gramm.app.Logger
import com.pr0gramm.app.io.Cache
import com.pr0gramm.app.util.BoundedInputStream
import com.pr0gramm.app.util.VideoBenchmark
import com.pr0gramm.app.util.closeQuietly
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A data source that uses a Cache as a data source.
 */
@OptIn(UnstableApi::class)
internal class InputStreamCacheDataSource(
    private val cache: Cache,
    private val httpClient: OkHttpClient,
    private val timing: VideoBenchmark,
    private val videoUri: Uri,
    private val videoEntry: Cache.Entry?,
) : BaseDataSource(true) {
    private val logger = Logger("InputStreamCacheDataSource(${this.hashCode()})")

    private var opened: Boolean = false
    private var _uri: Uri? = null

    private var inputStream: InputStream? = null
    private var cacheStream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    private var bytesTransferred: Long = 0
    private var openStarted = 0L
    private var readWaitMs = 0L
    private var firstRead = true
    private var sourceKind = "cache"

    private fun mark(phase: String) = timing.mark("source=${hashCode()} $phase")

    override fun open(dataSpec: DataSpec): Long {
        assert(_uri == null && inputStream == null) { "datasource already initialized" }

        logger.info {
            "Open dataSource for ${dataSpec.length} at ${dataSpec.position} of ${dataSpec.uri}"
        }

        _uri = dataSpec.uri
        openStarted = VideoBenchmark.now()
        mark("source_open media=${VideoBenchmark.key(dataSpec.uri)} position=${dataSpec.position} length=${dataSpec.length}")

        transferInitializing(dataSpec)

        if (dataSpec.uri == videoUri && videoEntry != null) {
            return openFromEntry(dataSpec, videoEntry)
        }
        return cache.get(dataSpec.uri).use { openFromEntry(dataSpec, it) }
    }

    private fun openFromEntry(dataSpec: DataSpec, entry: Cache.Entry): Long {
        mark("cache_entry localOrComplete=${entry.file != null}")
        // get the input stream from the entry.
        val cacheInput = entry.inputStreamAt(dataSpec.position.toInt())
        inputStream = cacheInput

        // gets the size of the file. This also initializes the cache entry.
        val totalSize = entry.totalSize.toLong()
        mark("cache_size bytes=$totalSize waitMs=${VideoBenchmark.now() - openStarted}")
        val rangeInput = openWebmTail(dataSpec, totalSize, cacheInput)
        if (rangeInput != null) cacheStream = cacheInput
        val source = rangeInput ?: cacheInput
        sourceKind = if (rangeInput == null) "cache" else "range"

        if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            this.bytesRemaining = totalSize - dataSpec.position
            inputStream = source
        } else {
            // limit amount to the requested length.
            this.inputStream = BoundedInputStream(source, dataSpec.length)
            this.bytesRemaining = dataSpec.length
        }

        // reduce read calls to the actual input
        this.inputStream = BufferedInputStream(this.inputStream, 1024 * 64)

        opened = true

        // everything looks fine, inform listeners about data transfer
        transferStarted(dataSpec)
        mark("source_ready path=$sourceKind openMs=${VideoBenchmark.now() - openStarted}")

        return bytesRemaining
    }

    private fun openWebmTail(spec: DataSpec, totalSize: Long, cacheInput: InputStream): InputStream? {
        val isHttp = spec.uri.scheme == "https" || spec.uri.scheme == "http"
        val tailSize = totalSize - spec.position
        if (!isHttp || spec.uri.path?.endsWith(".webm", ignoreCase = true) != true ||
            spec.position < 256 * 1024 || tailSize !in 1L..(256 * 1024L) || cacheInput.available() > 0
        ) return null

        val request = Request.Builder()
            .url(spec.uri.toString())
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=${spec.position}-${totalSize - 1}")
            .tag(VideoBenchmark.Http::class.java, VideoBenchmark.Http(timing, "tail"))
            .build()

        val started = VideoBenchmark.now()
        mark("tail_start position=${spec.position} bytes=$tailSize")
        try {
            val response = httpClient.newCall(request).execute()
            val expectedRange = "bytes ${spec.position}-${totalSize - 1}/$totalSize"
            if (response.code == 206 && response.header("Content-Range") == expectedRange) {
                mark("tail_ready requestMs=${VideoBenchmark.now() - started} bytes=$tailSize")
                logger.info { "Using HTTP range for WebM tail ($tailSize bytes at ${spec.position})" }
                return response.body.byteStream()
            }
            mark("tail_fallback code=${response.code} requestMs=${VideoBenchmark.now() - started}")
            response.close()
        } catch (err: IOException) {
            mark("tail_fallback type=${err.javaClass.simpleName} requestMs=${VideoBenchmark.now() - started}")
            logger.info { "WebM tail range failed, reading from cache: ${err.javaClass.simpleName}" }
        }
        return null
    }

    override fun close() {
        if (_uri != null) {
            mark("source_close path=$sourceKind bytes=$bytesTransferred readWaitMs=$readWaitMs openForMs=${VideoBenchmark.now() - openStarted}")
        }
        logger.info {
            "Closing dataSource for uri $_uri after reading $bytesTransferred with $bytesRemaining bytes remaining"
        }

        _uri = null

        inputStream?.closeQuietly()
        inputStream = null
        cacheStream?.closeQuietly()
        cacheStream = null

        bytesRemaining = C.LENGTH_UNSET.toLong()
        bytesTransferred = 0
        readWaitMs = 0
        firstRead = true
        sourceKind = "cache"

        if (opened) {
            opened = false
            transferEnded()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) {
            return 0
        }

        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            readLength.coerceAtMost(64 * 1024)
        } else {
            bytesRemaining.coerceAtMost(readLength.toLong()).toInt()
        }

        // read from input stream
        val stream = inputStream ?: throw IllegalStateException("DataSource is not open.")
        val started = VideoBenchmark.now()
        val bytesTransferred = try {
            stream.read(buffer, offset, bytesToRead)
        } finally {
            val waitMs = VideoBenchmark.now() - started
            readWaitMs += waitMs
            if (firstRead || waitMs >= 100) {
                mark("source_read path=$sourceKind first=$firstRead requested=$bytesToRead waitMs=$waitMs")
            }
            firstRead = false
        }

        if (bytesTransferred == -1) {
            // we did not get any data but we expected to get some.
            // indicate end of input to the caller.
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            // reduce by number of bytes read
            bytesRemaining -= bytesTransferred.toLong()
        }

        this.bytesTransferred += bytesTransferred.toLong()

        bytesTransferred(bytesTransferred)

        return bytesTransferred
    }

    override fun getUri(): Uri? = _uri
}
