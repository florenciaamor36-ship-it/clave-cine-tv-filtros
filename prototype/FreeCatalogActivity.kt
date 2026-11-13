package com.github.libretube.ui.activities

import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/** Search and play public media with an explicit reusable license from Internet Archive or Commons. */
class FreeCatalogActivity : AppCompatActivity() {
    private data class Media(val title: String, val description: String, val poster: String?, val stream: String, val provider: String, val license: String, val licenseUrl: String, val attribution: String, val pageUrl: String)

    private lateinit var body: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var queryInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var source = "archive"
    private val bg = Color.rgb(16, 12, 16)
    private val fg = Color.rgb(245, 243, 238)
    private val muted = Color.rgb(190, 182, 180)
    private val accent = Color.rgb(198, 160, 89)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "La Clave Play · Catálogo libre"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        setContentView(root)
        root.addView(TextView(this).apply { text = "LA CLAVE PLAY"; setTextColor(accent); textSize = 12f; letterSpacing = .14f; setTypeface(null, 1) })
        root.addView(TextView(this).apply { text = "Catálogo de medios libres"; setTextColor(fg); textSize = 23f; setTypeface(null, 1); setPadding(0, dp(5), 0, dp(3)) })
        root.addView(TextView(this).apply { text = "Resultados reales de Internet Archive y Wikimedia Commons. Cada ficha muestra su licencia y atribución."; setTextColor(muted); textSize = 13f; setPadding(0, 0, 0, dp(12)) })
        val sources = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sources.addView(button("Internet Archive", true) { source = "archive"; search() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        sources.addView(button("Wikimedia Commons", false) { source = "commons"; search() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        root.addView(sources)
        queryInput = EditText(this).apply {
            hint = "Buscá un título o tema"; setHintTextColor(muted); setTextColor(fg); textSize = 15f
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH; setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(47, 39, 42)); setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        }
        root.addView(queryInput, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        root.addView(button("Buscar", true) { search() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        playerView = PlayerView(this).apply { visibility = View.GONE; useController = true }
        root.addView(playerView, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(12) })
        progress = ProgressBar(this).apply { visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(accent) }
        root.addView(progress, LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER; topMargin = dp(12) })
        val scroll = ScrollView(this).apply { isFillViewport = false }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        results = body
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        showMessage("Buscá un título para consultar las fuentes públicas.")
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(if (primary) bg else fg)
        setBackgroundColor(if (primary) accent else Color.rgb(54, 43, 45)); setOnClickListener { action() }
    }

    private fun search() {
        val q = queryInput.text.toString().trim()
        if (q.length < 2) { showMessage("Escribí al menos dos caracteres para buscar."); return }
        progress.visibility = View.VISIBLE
        results.removeAllViews()
        lifecycleScope.launch {
            val (items, error) = withContext(Dispatchers.IO) {
                runCatching { if (source == "archive") searchArchive(q) else searchCommons(q) }
                    .fold({ it to null }, { emptyList<Media>() to (it.message ?: "No se pudo consultar la fuente.") })
            }
            progress.visibility = View.GONE
            results.removeAllViews()
            if (items.isEmpty()) showMessage(error ?: "No encontramos resultados reproducibles con licencia explícita. Probá otro término.")
            else items.forEach(::addCard)
        }
    }

    private fun searchArchive(q: String): List<Media> {
        val term = q.split(Regex("\\s+")).filter(String::isNotBlank).joinToString(" AND ") { "title:(${it.replace(Regex("[^\\p{L}\\p{N}'-]"), "")})" }
        val query = "mediatype:movies AND ($term)"
        val url = "https://archive.org/advancedsearch.php?q=${enc(query)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=description&rows=12&page=1&output=json"
        val docs = JSONObject(get(url)).getJSONObject("response").getJSONArray("docs")
        val output = mutableListOf<Media>()
        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            val id = doc.optString("identifier")
            if (id.isBlank()) continue
            val meta = runCatching { JSONObject(get("https://archive.org/metadata/${encPath(id)}")) }.getOrNull() ?: continue
            val md = meta.optJSONObject("metadata") ?: continue
            val licenseUrl = md.optString("licenseurl").ifBlank { md.optString("rights") }
            if (!allowedLicense(licenseUrl)) continue
            val files = meta.optJSONArray("files") ?: continue
            var stream: String? = null
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                val name = f.optString("name")
                val format = f.optString("format")
                if (Regex("\\.(mp4|m4v|webm|ogv|mkv)$", RegexOption.IGNORE_CASE).containsMatchIn(name) && !name.contains("_thumb", true) && f.optString("private") != "true") {
                    stream = "https://archive.org/download/${encPath(id)}/${encPath(name)}"; break
                }
                if (format.equals("MPEG4", true) && name.isNotBlank() && !name.contains("_thumb", true)) {
                    stream = "https://archive.org/download/${encPath(id)}/${encPath(name)}"; break
                }
            }
            if (stream == null) continue
            val title = doc.optString("title").ifBlank { id }
            output += Media(title, doc.optString("description").take(500), "https://archive.org/services/img/${encPath(id)}", stream, "Internet Archive", licenseLabel(licenseUrl), licenseUrl, md.optString("creator").ifBlank { md.optString("contributor") }, "https://archive.org/details/${encPath(id)}")
        }
        return output
    }

    private fun searchCommons(q: String): List<Media> {
        val search = "filemime:video ${q.trim()}"
        val api = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=15&gsrsearch=${enc(search)}&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=480&format=json&origin=*"
        val root = JSONObject(get(api)).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val output = mutableListOf<Media>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val page = root.optJSONObject(keys.next()) ?: continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val original = info.optString("url")
            if (!Regex("\\.(mp4|webm|ogv|ogg|mkv)$", RegexOption.IGNORE_CASE).containsMatchIn(original)) continue
            val ext = info.optJSONObject("extmetadata") ?: JSONObject()
            val license = html(ext.optJSONObject("LicenseShortName")?.optString("value").orEmpty())
            val licenseUrl = html(ext.optJSONObject("LicenseUrl")?.optString("value").orEmpty())
            if (!allowedLicense("$license $licenseUrl")) continue
            val thumb = info.optString("thumburl").ifBlank { null }
            val title = page.optString("title").removePrefix("File:").replace('_', ' ')
            val description = html(ext.optJSONObject("ImageDescription")?.optString("value").orEmpty()).take(500)
            val attribution = html(ext.optJSONObject("Artist")?.optString("value").orEmpty()).ifBlank { "Autoría no especificada" }
            output += Media(title, description, thumb, original, "Wikimedia Commons", license.ifBlank { "Licencia abierta" }, licenseUrl, attribution, "https://commons.wikimedia.org/wiki/${encPath(page.optString("title").replace(' ', '_'))}")
        }
        return output
    }

    private fun allowedLicense(value: String): Boolean {
        val v = value.lowercase()
        return listOf("creativecommons.org/licenses/by/", "creativecommons.org/licenses/by-sa/", "creativecommons.org/publicdomain/", "creativecommons.org/publicdomain/zero/", "creativecommons.org/publicdomain/mark/", "public domain").any(v::contains)
    }
    private fun licenseLabel(url: String) = when {
        url.contains("publicdomain", true) || url.contains("public domain", true) -> "Dominio público"
        url.contains("by-sa", true) -> "Creative Commons BY-SA"
        url.contains("/by/", true) -> "Creative Commons BY"
        else -> "Licencia abierta (ver fuente)"
    }
    private fun get(rawUrl: String): String {
        val c = URL(rawUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 16000; c.setRequestProperty("User-Agent", "LaClavePlay/1.0 (Android; open-media-catalog)")
        try { if (c.responseCode !in 200..299) error("Respuesta HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun encPath(value: String) = Uri.encode(value)
    private fun html(value: String) = android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun addCard(item: Media) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.rgb(38, 31, 34)) }
        card.addView(TextView(this).apply { text = item.title; setTextColor(fg); textSize = 17f; setTypeface(null, 1) })
        card.addView(TextView(this).apply { text = "${item.provider} · ${item.license}"; setTextColor(accent); textSize = 11f; setPadding(0, dp(4), 0, dp(4)) })
        if (item.description.isNotBlank()) card.addView(TextView(this).apply { text = item.description; setTextColor(muted); textSize = 12f; maxLines = 4 })
        card.addView(TextView(this).apply { text = "Atribución: ${item.attribution.ifBlank { "revisar fuente" }}"; setTextColor(muted); textSize = 10f; setPadding(0, dp(5), 0, 0) })
        card.addView(TextView(this).apply { text = "Abrir licencia y fuente ↗"; setTextColor(accent); textSize = 11f; setPadding(0, dp(4), 0, dp(5)); setOnClickListener { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(item.pageUrl))) } })
        card.addView(button("Reproducir", true) { play(item) }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        results.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }
    private fun play(item: Media) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also { exo -> playerView.player = exo; playerView.visibility = View.VISIBLE; exo.setMediaItem(PlayerMediaItem.fromUri(item.stream)); exo.prepare(); exo.playWhenReady = true }
    }
    private fun showMessage(message: String) { results.removeAllViews(); results.addView(TextView(this).apply { text = message; setTextColor(muted); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(16), dp(28), dp(16), dp(28)) }) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onDestroy() { playerView.player = null; player?.release(); player = null; super.onDestroy() }
}
