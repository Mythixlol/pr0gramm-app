package com.pr0gramm.app.util

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger

internal class VideoBenchmark(uri: Uri, private val label: String) {
    private val mediaKey = key(uri)
    val id = nextId.incrementAndGet()
    private val started = now()

    fun mark(phase: String) {
        Log.i("VideoBenchmark", "id=$id media=$mediaKey $label elapsedMs=${now() - started} $phase")
    }

    class Http(private val parent: VideoBenchmark, private val kind: String) : EventListener() {
        private val requestId = nextId.incrementAndGet()

        fun mark(phase: String) = parent.mark("http=$requestId kind=$kind $phase")

        override fun callStart(call: Call) = mark("call_start")
        override fun dnsStart(call: Call, domainName: String) = mark("dns_start host=$domainName")
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) =
            mark("dns_end")

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
            mark("connect_start")

        override fun secureConnectStart(call: Call) = mark("tls_start")
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) = mark("tls_end")
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
            mark("connect_end protocol=$protocol")

        override fun requestHeadersEnd(call: Call, request: okhttp3.Request) =
            mark("request_sent host=${request.url.host}")

        override fun responseHeadersEnd(call: Call, response: Response) =
            mark("response_headers code=${response.code} host=${response.request.url.host}")

        override fun responseBodyStart(call: Call) = mark("response_body_start")
        override fun responseBodyEnd(call: Call, byteCount: Long) = mark("response_body_end bytes=$byteCount")
        override fun callEnd(call: Call) = mark("call_end")
        override fun callFailed(call: Call, ioe: IOException) = mark("call_failed type=${ioe.javaClass.simpleName}")
    }

    companion object {
        private val nextId = AtomicInteger()
        fun now(): Long = SystemClock.elapsedRealtime()
        fun key(uri: Uri): String = uri.encodedPath.orEmpty().hashCode().toUInt().toString(16)

        fun forCache(uri: Uri): VideoBenchmark? {
            val path = uri.path.orEmpty()
            if (!path.endsWith(".webm", true) && !path.endsWith(".mp4", true)) return null
            return VideoBenchmark(uri, "cache")
        }
    }
}
