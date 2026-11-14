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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.core.view.children
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
import java.text.Normalizer
import java.util.Locale

/** Public media catalog: only playable items with explicit supported reuse terms are shown. */
class FreeCatalogActivity : AppCompatActivity() {
    private data class Media(val title: String, val description: String, val poster: String?, val stream: String, val provider: String, val license: String, val licenseUrl: String, val attribution: String, val pageUrl: String)
    private data class Shelf(val label: String, val query: String)

    private lateinit var results: LinearLayout
    private lateinit var queryInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var playerView: PlayerView
    private lateinit var archiveButton: Button
    private lateinit var commonsButton: Button
    private lateinit var categoryRow: LinearLayout
    private lateinit var emptyMessage: TextView
    private var player: ExoPlayer? = null
    private var source = "archive"
    private var selectedShelf = ""
    private val bg = Color.rgb(16, 12, 16)
    private val fg = Color.rgb(245, 243, 238)
    private val muted = Color.rgb(190, 182, 180)
    private val accent = Color.rgb(198, 160, 89)
    private val cardBg = Color.rgb(38, 31, 34)
    private val shelves = listOf(
        Shelf("Chaplin", "Charlie Chaplin"),
        Shelf("Buster Keaton", "Buster Keaton"),
        Shelf("Metrópolis", "Metropolis"),
        Shelf("Cine mudo", "silent film")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "La Clave Play · Catálogo libre"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        setContentView(root)
        root.addView(TextView(this).apply { text = "LA CLAVE PLAY"; setTextColor(accent); textSize = 12f; letterSpacing = .14f; setTypeface(null, 1) })
        root.addView(TextView(this).apply { text = "Explorá el catálogo"; setTextColor(fg); textSize = 23f; setTypeface(null, 1); setPadding(0, dp(5), 0, dp(3)) })
        root.addView(TextView(this).apply { text = "Películas libres disponibles en Internet Archive y Wikimedia Commons"; setTextColor(muted); textSize = 13f; setPadding(0, 0, 0, dp(12)) })
        val sources = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        archiveButton = button("Internet Archive", true) { setSource("archive") }
        commonsButton = button("Wikimedia Commons", false) { setSource("commons") }
        sources.addView(archiveButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        sources.addView(commonsButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        root.addView(sources)
        root.addView(TextView(this).apply { text = "Explorar por colección"; setTextColor(fg); textSize = 17f; setTypeface(null, 1); setPadding(dp(2), dp(14), 0, dp(5)) })
        val categoryScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryScroll.addView(categoryRow)
        root.addView(categoryScroll)
        shelves.forEach { shelf -> categoryRow.addView(button(shelf.label, shelf.label == shelves.first().label) { browse(shelf) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) }) }
        queryInput = EditText(this).apply {
            hint = "Buscar un título exacto"; setHintTextColor(muted); setTextColor(fg); textSize = 15f
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH; setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(47, 39, 42)); setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { searchExact(); true } else false }
        }
        root.addView(queryInput, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        root.addView(button("Buscar título", true) { searchExact() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        playerView = PlayerView(this).apply { visibility = View.GONE; useController = true }
        root.addView(playerView, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(12) })
        progress = ProgressBar(this).apply { visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(accent) }
        root.addView(progress, LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER; topMargin = dp(8) })
        val scroll = ScrollView(this).apply { isFillViewport = false }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyMessage = TextView(this).apply { setTextColor(muted); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), dp(24)) }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        setSource("archive")
        browse(shelves.first())
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(if (primary) bg else fg)
        setBackgroundColor(if (primary) accent else Color.rgb(54, 43, 45)); setOnClickListener { action() }
    }

    private fun setSource(value: String) {
        source = value
        if (::archiveButton.isInitialized) {
            archiveButton.setBackgroundColor(if (source == "archive") accent else Color.rgb(54, 43, 45))
            archiveButton.setTextColor(if (source == "archive") bg else fg)
            commonsButton.setBackgroundColor(if (source == "commons") accent else Color.rgb(54, 43, 45))
            commonsButton.setTextColor(if (source == "commons") bg else fg)
        }
        if (selectedShelf.isNotBlank()) browse(shelves.firstOrNull { it.label == selectedShelf } ?: shelves.first())
    }

    private fun browse(shelf: Shelf) {
        selectedShelf = shelf.label
        queryInput.setText("")
        categoryRow.children.filterIsInstance<Button>().forEach { chip ->
            val active = chip.text.toString() == shelf.label
            chip.setBackgroundColor(if (active) accent else Color.rgb(54, 43, 45)); chip.setTextColor(if (active) bg else fg)
        }
        load(shelf.query, exact = true)
    }

    private fun searchExact() {
        val query = queryInput.text.toString().trim()
        if (query.length < 2) { showMessage("Escribí al menos dos caracteres para buscar un título."); return }
        selectedShelf = ""
        categoryRow.children.filterIsInstance<Button>().forEach { it.setBackgroundColor(Color.rgb(54, 43, 45)); it.setTextColor(fg) }
        load(query, exact = true)
    }

    private fun load(query: String, exact: Boolean) {
        progress.visibility = View.VISIBLE
        results.removeAllViews()
        lifecycleScope.launch {
            val (items, error) = withContext(Dispatchers.IO) {
                runCatching { if (source == "archive") searchArchive(query, exact) else searchCommons(query, exact) }
                    .fold({ it to null }, { emptyList<Media>() to (it.message ?: "No se pudo consultar la fuente.") })
            }
            progress.visibility = View.GONE
            results.removeAllViews()
            if (items.isEmpty()) showMessage(error ?: "No encontramos títulos reproducibles con licencia explícita en esta colección.")
            else items.forEach(::addCard)
        }
    }

    private fun searchArchive(q: String, exact: Boolean): List<Media> {
        val terms = significantWords(q)
        val clauses = terms.joinToString(" AND ") { "title:($it)" }
        val query = if (clauses.isBlank()) "mediatype:movies" else "mediatype:movies AND ($clauses)"
        val url = "https://archive.org/advancedsearch.php?q=${enc(query)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=description&rows=40&page=1&output=json"
        val docs = JSONObject(get(url)).getJSONObject("response").getJSONArray("docs")
        val output = mutableListOf<Media>()
        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            val id = doc.optString("identifier")
            val title = doc.optString("title").ifBlank { id }
            if (id.isBlank() || !titleMatches(title, terms, exact)) continue
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
                val videoExt = Regex("\\.(mp4|m4v|webm|ogv)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
                val videoFormat = listOf("mpeg4", "h.264", "webm", "ogg video").any { format.contains(it, true) }
                if ((videoExt || videoFormat) && name.isNotBlank() && !name.contains("_thumb", true) && f.optString("source") != "metadata" && f.optString("private") != "true") {
                    stream = "https://archive.org/download/${encPath(id)}/${encPath(name)}"; break
                }
            }
            if (stream == null) continue
            output += Media(title, doc.optString("description").take(500), "https://archive.org/services/img/${encPath(id)}", stream, "Internet Archive", licenseLabel(licenseUrl), licenseUrl, md.optString("creator").ifBlank { md.optString("contributor") }, "https://archive.org/details/${encPath(id)}")
            if (output.size >= 18) break
        }
        return output
    }

    private fun searchCommons(q: String, exact: Boolean): List<Media> {
        val search = "filemime:video ${q.trim()}"
        val api = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=30&gsrsearch=${enc(search)}&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=480&format=json&origin=*"
        val root = JSONObject(get(api)).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val output = mutableListOf<Media>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val page = root.optJSONObject(keys.next()) ?: continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val original = info.optString("url")
            if (!Regex("\\.(mp4|webm|ogv|ogg)$", RegexOption.IGNORE_CASE).containsMatchIn(original)) continue
            val ext = info.optJSONObject("extmetadata") ?: continue
            val license = html(ext.optJSONObject("LicenseShortName")?.optString("value").orEmpty())
            val licenseUrl = html(ext.optJSONObject("LicenseUrl")?.optString("value").orEmpty())
            if (!allowedLicense("$license $licenseUrl")) continue
            val title = page.optString("title").removePrefix("File:").replace('_', ' ')
            if (!titleMatches(title, significantWords(q), exact)) continue
            val thumb = info.optString("thumburl").ifBlank { null }
            val description = html(ext.optJSONObject("ImageDescription")?.optString("value").orEmpty()).take(500)
            val attribution = html(ext.optJSONObject("Artist")?.optString("value").orEmpty()).ifBlank { "Autoría no especificada" }
            output += Media(title, description, thumb, original, "Wikimedia Commons", license, licenseUrl, attribution, "https://commons.wikimedia.org/wiki/${encPath(page.optString("title").replace(' ', '_'))}")
            if (output.size >= 18) break
        }
        return output
    }

    private fun significantWords(value: String): List<String> {
        val ignored = setOf("the", "and", "for", "with", "from", "una", "uno", "los", "las", "del", "de", "la", "el", "en", "y", "a", "an", "of", "in")
        return normalize(value).split(Regex("\\s+")).filter { it.length >= 3 && it !in ignored }.distinct()
    }

    private fun titleMatches(title: String, terms: List<String>, exact: Boolean): Boolean {
        if (terms.isEmpty()) return true
        val titleWords = normalize(title).split(Regex("\\s+")).toSet()
        return if (exact) terms.all { it in titleWords } else terms.any { it in titleWords || titleWords.any { word -> word.startsWith(it) } }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun allowedLicense(value: String): Boolean {
        val v = value.lowercase(Locale.ROOT)
        if (listOf("by-nc", "by/nd", "by-nd", "noncommercial", "non-commercial", "no derivatives", "no-derivatives").any(v::contains)) return false
        return v.contains("public domain") || v.contains("publicdomain/mark") || v.contains("publicdomain/zero") || Regex("\\bcc0\\b").containsMatchIn(v) || v.contains("creativecommons.org/licenses/by/") || v.contains("creativecommons.org/licenses/by-sa/") || Regex("\\bcc by(?: |-|$)").containsMatchIn(v) || Regex("\\bcc by-sa(?: |-|$)").containsMatchIn(v)
    }

    private fun licenseLabel(value: String) = when {
        value.contains("publicdomain", true) || value.contains("public domain", true) || value.contains("cc0", true) -> "Dominio público / CC0"
        value.contains("by-sa", true) -> "Creative Commons BY-SA"
        value.contains("/by/", true) || Regex("\\bcc by(?: |-|$)").containsMatchIn(value.lowercase(Locale.ROOT)) -> "Creative Commons BY"
        else -> "Licencia abierta (ver ficha original)"
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
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(cardBg) }
        val image = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(190)); scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = item.title }
        if (!item.poster.isNullOrBlank()) com.github.libretube.helpers.ImageHelper.loadImage(item.poster, image)
        card.addView(image)
        card.addView(TextView(this).apply { text = item.title; setTextColor(fg); textSize = 17f; setTypeface(null, 1); setPadding(0, dp(10), 0, 0) })
        card.addView(TextView(this).apply { text = "${item.provider} · ${item.license}"; setTextColor(accent); textSize = 11f; setPadding(0, dp(4), 0, dp(4)) })
        if (item.description.isNotBlank()) card.addView(TextView(this).apply { text = item.description; setTextColor(muted); textSize = 12f; maxLines = 4 })
        card.addView(TextView(this).apply { text = "Atribución: ${item.attribution.ifBlank { "revisar ficha original" }}"; setTextColor(muted); textSize = 10f; setPadding(0, dp(5), 0, 0) })
        card.addView(TextView(this).apply { text = "Abrir licencia y fuente ↗"; setTextColor(accent); textSize = 11f; setPadding(0, dp(4), 0, dp(5)); setOnClickListener { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(item.pageUrl))) } })
        card.addView(button("Reproducir", true) { play(item) }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        results.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }
    private fun showMessage(message: String) { results.removeAllViews(); emptyMessage.text = message; results.addView(emptyMessage) }
    private fun play(item: Media) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also { exo -> playerView.player = exo; playerView.visibility = View.VISIBLE; exo.setMediaItem(PlayerMediaItem.fromUri(item.stream)); exo.prepare(); exo.playWhenReady = true }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onDestroy() { playerView.player = null; player?.release(); player = null; super.onDestroy() }
}
