package com.donkey

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.net.Uri
import android.webkit.*
import android.widget.FrameLayout
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CookieJar
import okhttp3.Request as OkRequest
import kotlin.coroutines.resume

class Donkey(private val context: Context) : MainAPI() {
    override var mainUrl = "https://donkey.to"
    override var name = "Donkey"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbToken = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI0MmQ4MDdmNzM1OGRjZWE4NTAyOTFkNzM3YWM0Mjg1MiIsIm5iZiI6MTcyMzIwNDQ0Ni4zODQ5NzksInN1YiI6IjY2ODI2ZDA5OWU1MThkYjA1YjFiNzVmNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.GuVTSK_Z-CWJOFalwiKu8-FzilqPIBROlQ71-eMBZz0"
    private val img500 = "https://image.tmdb.org/t/p/w500"
    private val imgOrig = "https://image.tmdb.org/t/p/original"
    private val gson = Gson()

    companion object {
        var lastValidUserAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
    }

    private fun headers() = mapOf(
        "Authorization" to "Bearer $tmdbToken",
        "Accept" to "application/json"
    )

    private fun fixPoster(path: String?) = path?.let { img500 + it }

    private fun fixBackdrop(path: String?) = path?.let { imgOrig + it }

    @Suppress("UNCHECKED_CAST")
    private fun parsePage(json: String): Triple<List<Map<String, Any?>>, Int, Int> {
        val map = gson.fromJson(json, Map::class.java) as Map<String, Any?>
        val results = (map["results"] as? List<Map<String, Any?>>) ?: emptyList()
        val page = (map["page"] as? Double)?.toInt() ?: 1
        val totalPages = (map["total_pages"] as? Double)?.toInt() ?: 1
        return Triple(results, page, totalPages)
    }

    private fun resultToSearch(item: Map<String, Any?>): SearchResponse? {
        val id = (item["id"] as? Double)?.toInt() ?: return null
        val title = (item["title"] as? String) ?: (item["name"] as? String) ?: return null
        val poster = fixPoster(item["poster_path"] as? String)
        val mediaType = item["media_type"] as? String
        val type = when {
            mediaType == "tv" -> TvType.TvSeries
            item.containsKey("first_air_date") -> TvType.TvSeries
            else -> TvType.Movie
        }
        val searchUrl = if (type == TvType.TvSeries) "tv/$id" else "movie/$id"
        return newMovieSearchResponse(title, searchUrl, type) {
            this.posterUrl = poster
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbBase/trending/movie/day?language=en" to "Trending Movies",
        "$tmdbBase/trending/tv/day?language=en" to "Trending TV",
        "$tmdbBase/movie/popular?language=en" to "Popular Movies",
        "$tmdbBase/tv/popular?language=en" to "Popular TV",
        "$tmdbBase/trending/all/day?language=en" to "Trending All"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}&page=$page" else request.data
        val (results, _, totalPages) = parsePage(app.get(url, headers = headers()).text)
        val items = results.mapNotNull { resultToSearch(it) }
        return newHomePageResponse(request.name, items, totalPages > page)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$tmdbBase/search/multi?query=${java.net.URLEncoder.encode(query, "UTF-8")}&include_adult=false&language=en&page=$page"
        val (results, _, totalPages) = parsePage(app.get(url, headers = headers()).text)
        val items = results.mapNotNull { resultToSearch(it) }
        return newSearchResponseList(items, totalPages > page)
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("/")
        if (parts.size < 2) return null
        val type = parts[0]
        val id = parts[1].toIntOrNull() ?: return null
        return when (type) {
            "tv" -> loadTv(id, url)
            else -> loadMovie(id, url)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun loadMovie(id: Int, url: String): LoadResponse? {
        val json = app.get("$tmdbBase/movie/$id?language=en&append_to_response=credits,similar", headers = headers()).text
        val m = gson.fromJson(json, Map::class.java) as Map<String, Any?>
        val title = m["title"] as? String ?: return null
        val poster = fixPoster(m["poster_path"] as? String)
        val bg = fixBackdrop(m["backdrop_path"] as? String)
        val plot = m["overview"] as? String
        val year = (m["release_date"] as? String)?.take(4)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = plot
            this.year = year?.toIntOrNull()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun loadTv(id: Int, url: String): LoadResponse? {
        val json = app.get("$tmdbBase/tv/$id?language=en&append_to_response=credits,similar", headers = headers()).text
        val m = gson.fromJson(json, Map::class.java) as Map<String, Any?>
        val title = m["name"] as? String ?: return null
        val poster = fixPoster(m["poster_path"] as? String)
        val bg = fixBackdrop(m["backdrop_path"] as? String)
        val plot = m["overview"] as? String

        val seasonsRaw = m["seasons"] as? List<Map<String, Any?>>
        val seasons = seasonsRaw?.filter { (it["season_number"] as? Double)?.toInt() ?: 0 > 0 } ?: emptyList()

        val episodes = coroutineScope {
            seasons.flatMap { s ->
                val sn = (s["season_number"] as? Double)?.toInt() ?: return@flatMap emptyList<Episode>()
                try {
                    val sj = app.get("$tmdbBase/tv/$id/season/$sn?language=en", headers = headers()).text
                    val sm = gson.fromJson(sj, Map::class.java) as Map<String, Any?>
                    val eps = sm["episodes"] as? List<Map<String, Any?>> ?: emptyList()
                    eps.map { ep ->
                        val en = (ep["episode_number"] as? Double)?.toInt() ?: 1
                        newEpisode("tv/$id/$sn/$en") {
                            this.name = ep["name"] as? String ?: "Episode $en"
                            this.episode = en
                            this.season = sn
                            this.posterUrl = fixPoster(ep["still_path"] as? String)
                        }
                    }
                } catch (e: Exception) { emptyList() }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = plot
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(embedUrl: String, referer: String): String? = suspendCancellableCoroutine { cont ->
        val activity = context as? Activity
        if (activity == null || activity.isFinishing) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val finalUrl = embedUrl.replace("&amp;", "&").trim()
        val originalHost = try { Uri.parse(finalUrl).host?.replace("www.", "") ?: "" } catch (e: Exception) { "" }

        activity.runOnUiThread {
            val dialog = Dialog(activity)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                attributes = attributes?.apply {
                    width = 1
                    height = 1
                    x = -10000
                    y = -10000
                    gravity = Gravity.START or Gravity.TOP
                }
            }

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }

            try {
                dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1))
                dialog.show()
            } catch (e: Exception) {
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    decor?.addView(webView, FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP))
                } catch (_: Exception) {}
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowContentAccess = true
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = lastValidUserAgent
                blockNetworkImage = true
            }

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            val cookieManager = CookieManager.getInstance()
            try {
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                cookieManager.flush()
            } catch (_: Exception) {}

            val client = app.baseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(CookieJar.NO_COOKIES)
                .build()

            val foundM3u8 = linkedSetOf<String>()
            var finished = false
            val finishLock = Any()
            val handler = Handler(Looper.getMainLooper())

            fun cleanup() {
                try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                try { webView.stopLoading() } catch (_: Exception) {}
                try { webView.destroy() } catch (_: Exception) {}
                try { cookieManager.flush() } catch (_: Exception) {}
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
            }

            fun safeFinish(result: String?) {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                try { if (cont.isActive) cont.resume(result) } catch (_: Exception) {}
                cleanup()
            }

            fun handleFoundLink(url: String) {
                val clean = url.substringBefore("?")
                if (!clean.endsWith(".m3u8")) return
                synchronized(foundM3u8) {
                    if (!foundM3u8.contains(url)) {
                        foundM3u8.add(url)
                        handler.postDelayed({
                            safeFinish(if (clean.contains("master") || clean.contains("playlist") || clean.contains("index")) url else url)
                        }, if (clean.contains("master") || clean.contains("playlist") || clean.contains("index")) 300 else 1500)
                    }
                }
            }

            val snifferJs = """
                (function() {
                    if (!window.__NET_HOOKED__) {
                        window.__NET_HOOKED__ = true;
                        window.open = function() { return null; };
                        var _fetch = window.fetch;
                        if (_fetch) {
                            window.fetch = function() {
                                return _fetch.apply(this, arguments).then(function(resp) {
                                    try {
                                        var u = resp && resp.url ? resp.url : '';
                                        if (u && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                    } catch(e){}
                                    return resp;
                                });
                            };
                        }
                        var _open = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, u) {
                            this.addEventListener('load', function() {
                                try {
                                    if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                } catch(e){}
                            });
                            return _open.apply(this, arguments);
                        };
                    }
                })();
            """.trimIndent()

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url.toString()
                    if (!url.startsWith("http")) return true
                    if (url.contains("policies.google.com") || url.contains("recaptcha") || url.contains("mcaptcha")) {
                        Handler(Looper.getMainLooper()).post { view?.loadUrl(finalUrl, mapOf("Referer" to referer)) }
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(snifferJs, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(snifferJs, null)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    val lower = url.lowercase()

                    if (lower.endsWith(".m3u8") || lower.contains(".m3u8")) {
                        handleFoundLink(url)
                        try {
                            val reqBuilder = OkRequest.Builder().url(url)
                                .header("User-Agent", lastValidUserAgent)
                                .header("Referer", referer)
                            try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}
                            val response = client.newCall(reqBuilder.build()).execute()
                            if (!response.isSuccessful) return null
                            response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                            val contentType = response.header("content-type")?.split(";")?.first() ?: "application/vnd.apple.mpegurl"
                            return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                        } catch (e: Exception) { return null }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    val msg = cm?.message() ?: ""
                    if (msg.startsWith("NET_M3U8::")) {
                        handleFoundLink(msg.substringAfter("::").trim())
                    }
                    return true
                }

                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    try {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        val adWebView = WebView(activity).apply {
                            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP)
                            visibility = View.INVISIBLE
                        }
                        adWebView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = lastValidUserAgent
                        }
                        try { (activity.window?.decorView as? ViewGroup)?.addView(adWebView) } catch (_: Exception) {}
                        transport?.webView = adWebView
                        resultMsg?.sendToTarget()
                        handler.postDelayed({
                            try { (adWebView.parent as? ViewGroup)?.removeView(adWebView); adWebView.destroy() } catch (_: Exception) {}
                        }, 1000)
                        return true
                    } catch (e: Exception) { return false }
                }
            }

            handler.postDelayed({ safeFinish(foundM3u8.firstOrNull()) }, 20000)
            webView.loadUrl(finalUrl, mapOf("Referer" to referer))

            cont.invokeOnCancellation { handler.post { safeFinish(null) } }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("/")
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val type = parts[0]
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val embedSources = listOf(
            "peachify.top",
            "player.videasy.net",
            "vsembed.ru"
        )

        val suffix = if (type == "tv" && season != null && episode != null) "tv/$id/$season/$episode" else "movie/$id"
        val accentParams = mapOf("peachify.top" to "accent=FFA500", "player.videasy.net" to "color=FFA500")

        var foundLink = false
        for (host in embedSources) {
            if (foundLink) break
            try {
                val param = accentParams[host]?.let { "?$it" } ?: ""
                val embedUrl = "https://$host/embed/$suffix$param"
                val m3u8 = resolveWithWebView(embedUrl, "$mainUrl/")
                if (!m3u8.isNullOrBlank()) {
                    foundLink = true
                    M3u8Helper.generateM3u8(
                        source = "$name ($host)",
                        streamUrl = m3u8,
                        referer = embedUrl,
                        headers = mapOf("Referer" to embedUrl, "User-Agent" to lastValidUserAgent)
                    ).forEach(callback)
                }
            } catch (_: Exception) {}
        }

        return foundLink
    }
}
