package com.pr0gramm.app.util

import com.pr0gramm.app.Pr0Domain
import com.pr0gramm.app.Settings
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Calendar

class MediaServerInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (Settings.useSecondaryServers) {
            return chain.proceed(request.newBuilder().url(injectSecondaryServer(url)).build())
        }

        val tier = if (request.method == "GET" && url.host == "vid.$Pr0Domain") {
            videoTier(url.encodedPath)
        } else {
            null
        }
        if (tier == null) return chain.proceed(request)

        val tierUrl = url.newBuilder().host("vid-$tier.$Pr0Domain").build()
        val response = chain.proceed(request.newBuilder().url(tierUrl).build())
        if (response.code != 404 && response.code != 410) return response

        // A file can move between tiers. The canonical host remains our fallback.
        response.close()
        return chain.proceed(request)
    }

    private fun videoTier(path: String): String? {
        val match = videoDate.find(path) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val today = Calendar.getInstance()
        val ageInMonths = (today.get(Calendar.YEAR) - year) * 12 +
                (today.get(Calendar.MONTH) + 1 - month)
        return when {
            ageInMonths <= 1 -> "hot"
            ageInMonths <= 12 -> "warm"
            ageInMonths <= 36 -> "cool"
            ageInMonths <= 72 -> "cold"
            else -> "freeze"
        }
    }

    private val videoDate = Regex("^/(\\d{4})/(0[1-9]|1[0-2])/\\d{2}/")

    private fun injectSecondaryServer(url: HttpUrl): HttpUrl {
        val (subdomain, domain) = url.host.split('.', limit = 2).takeIf { it.size == 2 } ?: return url
        if (domain != Pr0Domain && domain != "pr0gramm.com") {
            return url
        }

        val secondary = when (subdomain) {
            "img" -> "images"
            "vid" -> "videos"
            "full" -> "fullsize"
            else -> return url
        }

        return url.newBuilder().host("$secondary.$domain").build()
    }
}
