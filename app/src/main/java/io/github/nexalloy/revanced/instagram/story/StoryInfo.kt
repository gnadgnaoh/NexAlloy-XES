package io.github.nexalloy.revanced.instagram.story

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.nexalloy.R
import io.github.nexalloy.revanced.instagram.download.I18n
import io.github.nexalloy.revanced.instagram.download.MediaResolver
import io.github.nexalloy.revanced.instagram.download.ModuleLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

internal object StoryInfo {

    val getters = LinkedHashMap<String, Method>()

    private const val MAX_DEPTH = 7
    private const val PROFILE_URL = "https://www.instagram.com/"

    private fun raw(media: Any, key: String): Any? =
        getters[key]?.let { g -> runCatching { g.invoke(media) }.getOrNull() }

    fun value(media: Any, key: String): Any? = tree(raw(media, key), 0, identitySet())

    fun list(media: Any, key: String): List<Any> = when (val v = value(media, key)) {
        null -> emptyList()
        is List<*> -> v.filterNotNull()
        else -> listOf(v)
    }

    fun hasQuiz(media: Any) = list(media, "story_quizs").isNotEmpty()
    fun hasPoll(media: Any) = list(media, "story_polls").isNotEmpty()
    fun hasSlider(media: Any) = list(media, "story_sliders").isNotEmpty()

    private fun identitySet(): MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    private val mapMethods = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()
    private val serializerArgs = ConcurrentHashMap<Class<*>, java.util.Optional<Any>>()

    private fun allMethods(c: Class<*>): List<Method> =
        generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java }.flatMap { it.declaredMethods.asSequence() }.toList()

    private fun allFields(c: Class<*>): List<Field> =
        generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java }.flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }.toList()

    private fun serializerArg(type: Class<*>): Any? = serializerArgs.getOrPut(type) {
        java.util.Optional.ofNullable(runCatching { type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull())
    }.orElse(null)

    private fun mapMethod(c: Class<*>): Method? = mapMethods.getOrPut(c) {
        var zero: Method? = null
        var one: Method? = null
        for (m in allMethods(c)) {
            if (m.returnType != Map::class.java || Modifier.isStatic(m.modifiers) || !m.isSynthetic) continue
            if (m.parameterCount == 0 && zero == null) zero = m
            else if (m.parameterCount == 1 && one == null && m.parameterTypes[0].name.startsWith("X.") &&
                serializerArg(m.parameterTypes[0]) != null) one = m
        }
        java.util.Optional.ofNullable((one ?: zero)?.apply { isAccessible = true })
    }.orElse(null)

    private fun tree(o: Any?, depth: Int, seen: MutableSet<Any>): Any? {
        if (o == null || depth > MAX_DEPTH) return null
        if (o is CharSequence) return o.toString()
        if (o is Number || o is Boolean) return o
        if (o is Enum<*>) return o.toString()
        if (!seen.add(o)) return null
        if (o is Map<*, *>) {
            val out = LinkedHashMap<String, Any>()
            for ((k, v) in o) {
                val key = k.toString()
                if (key == "dash_manifest") continue
                tree(v, depth + 1, seen)?.let { out[key] = it }
            }
            return out
        }
        if (o is Collection<*>) return o.mapNotNull { tree(it, depth + 1, seen) }
        val name = o.javaClass.name
        if (!name.startsWith("com.instagram.") && !name.startsWith("X.") && !name.startsWith("com.facebook.")) return o.toString()
        mapMethod(o.javaClass)?.let { m ->
            val r = runCatching { if (m.parameterCount == 0) m.invoke(o) else m.invoke(o, serializerArg(m.parameterTypes[0])) }.getOrNull()
            if (r is Map<*, *>) return tree(r, depth, seen)
        }
        val out = LinkedHashMap<String, Any>()
        for (f in allFields(o.javaClass)) {
            runCatching { f.isAccessible = true; tree(f.get(o), depth + 1, seen) }.getOrNull()?.let { out["_" + f.name] = it }
        }
        return out
    }

    fun find(tree: Any?, key: String): Any? {
        when (tree) {
            is Map<*, *> -> {
                tree[key]?.let { return it }
                for (v in tree.values) find(v, key)?.let { return it }
            }
            is List<*> -> for (v in tree) find(v, key)?.let { return it }
        }
        return null
    }

    fun str(tree: Any?, key: String): String? = plain(find(tree, key))

    private fun plain(v: Any?): String? =
        if (v == null || v is Map<*, *> || v is List<*>) null else v.toString().trim().ifEmpty { null }

    private fun num(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.toDoubleOrNull()?.toLong()
        else -> null
    }

    private fun time(v: Any?): String? {
        var secs = num(v) ?: return null
        if (secs <= 0) return null
        if (secs > 100_000_000_000L) secs /= 1000
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(secs * 1000))
    }

    private fun usernames(root: Any?): List<String> {
        val out = ArrayList<String>()
        collectUsers(root, 0, out, identitySet())
        if (out.isEmpty()) collectUsernameKeys(tree(root, 0, identitySet()), out)
        return out
    }

    private fun collectUsers(o: Any?, depth: Int, out: MutableList<String>, seen: MutableSet<Any>) {
        if (o == null || depth > 5 || o is CharSequence || o is Number || o is Boolean || !seen.add(o)) return
        MediaResolver.usernameOfUser(o)?.takeIf { o.javaClass.name == USER_CLASS }?.let { if (it !in out) out += it; return }
        when (o) {
            is Collection<*> -> { o.forEach { collectUsers(it, depth + 1, out, seen) }; return }
            is Map<*, *> -> { o.values.forEach { collectUsers(it, depth + 1, out, seen) }; return }
        }
        val n = o.javaClass.name
        if (!n.startsWith("com.instagram.") && !n.startsWith("X.")) return
        for (f in allFields(o.javaClass)) {
            if (f.type.isPrimitive) continue
            runCatching { f.isAccessible = true; collectUsers(f.get(o), depth + 1, out, seen) }
        }
    }

    private fun collectUsernameKeys(t: Any?, out: MutableList<String>) {
        when (t) {
            is Map<*, *> -> {
                (t["username"] as? String)?.takeIf { it.isNotEmpty() && it !in out }?.let { out += it }
                t.values.forEach { collectUsernameKeys(it, out) }
            }
            is List<*> -> t.forEach { collectUsernameKeys(it, out) }
        }
    }

    private const val USER_CLASS = "com.instagram.user.model.User"

    private class Sheet(val ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        private val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = Color.parseColor(if (dark) "#1C1C1E" else "#F2F2F7")
        val text = if (dark) Color.WHITE else Color.parseColor("#1C1C1E")
        val sub = Color.parseColor(if (dark) "#AEAEB2" else "#6C6C70")
        val field = Color.parseColor(if (dark) "#2C2C2E" else "#FFFFFF")
        val accent = Color.parseColor("#0A84FF")
        val green = Color.parseColor("#8C30D158")
        val dialog = Dialog(ctx)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var rows = 0

        fun px(v: Int) = (v * dp).toInt()

        fun round(color: Int, radius: Float): Drawable = GradientDrawable().apply { setColor(color); cornerRadius = radius * dp }

        fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

        fun text(value: String, color: Int, size: Float, bold: Boolean = false) = TextView(ctx).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

        fun spaced(view: View, top: Int = 8): View = view.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(top) }
        }

        fun row(@StringRes label: Int, value: String?, link: String? = null) {
            if (value.isNullOrBlank()) return
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = round(field, 14f)
                setPadding(px(16), px(10), px(16), px(10))
                addView(text(I18n.t(ctx, label), sub, 12f))
                addView(text(value, if (link != null) accent else this@Sheet.text, 15f))
                setOnClickListener { if (link != null) open(link) else copy(value) }
                setOnLongClickListener { copy(link ?: value); true }
            }
            body.addView(spaced(box))
            rows++
        }

        fun heading(value: String) { body.addView(spaced(text(value, text, 16f, true), 12)) }

        fun note(value: String) { body.addView(spaced(text(value, sub, 13f), 6)) }

        fun bar(left: String, right: String, fraction: Double, fill: Int, bold: Boolean) {
            val filled = ClipDrawable(round(fill, 14f), Gravity.START, ClipDrawable.HORIZONTAL)
            filled.level = (fraction.coerceIn(0.0, 1.0) * 10000).toInt()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), px(13), px(16), px(13))
                background = LayerDrawable(arrayOf(round(field, 14f), filled))
                addView(text(left, this@Sheet.text, 15f, bold), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(right, this@Sheet.text, 14f, true), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10) })
                setOnClickListener { copy(left) }
            }
            body.addView(spaced(row))
        }

        fun button(value: String, onClick: () -> Unit) {
            val b = text(value, accent, 15f, true).apply {
                gravity = Gravity.CENTER
                background = round(field, 14f)
                setPadding(px(16), px(12), px(16), px(12))
                setOnClickListener { onClick() }
            }
            body.addView(spaced(b))
        }

        fun copy(value: String) {
            runCatching {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("story", value))
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_si_copied), Toast.LENGTH_SHORT).show()
            }
        }

        fun open(url: String) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (url.startsWith(PROFILE_URL)) intent.setPackage(ctx.packageName)
                ctx.startActivity(intent)
            }
        }

        fun show(title: String, hint: String?) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val sheet = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = round(bg, 20f)
                setPadding(px(20), px(12), px(20), px(24))
            }
            sheet.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(px(40), px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(14) }
                background = round(Color.parseColor("#8E8E93"), 2f)
            })
            sheet.addView(text(title, text, 18f, true))
            if (!hint.isNullOrEmpty()) sheet.addView(text(hint, sub, 13f))
            val scroll = ScrollView(ctx).apply { addView(body) }
            val maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.7).toInt()
            sheet.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            scroll.viewTreeObserver.addOnGlobalLayoutListener {
                if (scroll.height > maxHeight) scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
            }
            val close = text(I18n.t(ctx, R.string.ig_si_close), text, 15f, true).apply {
                gravity = Gravity.CENTER
                background = round(alpha(this@Sheet.text, 0x1F), 14f)
                setPadding(px(16), px(13), px(16), px(13))
                setOnClickListener { dialog.dismiss() }
            }
            sheet.addView(spaced(close, 12))
            dialog.setContentView(sheet)
            dialog.window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.setGravity(Gravity.BOTTOM)
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                w.attributes = w.attributes.apply { x = px(12); y = px(12) }
            }
            dialog.show()
        }
    }

    fun showInfo(ctx: Context, media: Any) = guarded {
        val s = Sheet(ctx)
        s.row(R.string.ig_si_posted, time(value(media, "taken_at")))
        s.row(R.string.ig_si_expires, time(value(media, "expiring_at")))
        val edited = time(value(media, "story_edited_at"))
            ?: if (value(media, "is_story_edited") == true) I18n.t(ctx, R.string.ig_si_yes) else null
        s.row(R.string.ig_si_edited, edited)

        music(media)?.let { (line, query) ->
            s.row(R.string.ig_si_music, line)
            if (query != null) {
                val q = Uri.encode(query)
                listOf(
                    "Spotify" to "https://open.spotify.com/search/$q",
                    "YouTube Music" to "https://music.youtube.com/search?q=$q",
                    "Apple Music" to "https://music.apple.com/search?term=$q",
                ).forEach { (name, url) -> s.button(I18n.t(ctx, R.string.ig_si_open_in, name)) { s.open(url) } }
            }
        }

        val places = list(media, "story_locations").mapNotNull { str(find(it, "location"), "name") ?: str(it, "name") }.distinct()
        s.row(R.string.ig_si_location, places.joinToString("\n"))
        val tags = list(media, "story_hashtags").mapNotNull { (str(find(it, "hashtag"), "name") ?: str(it, "name"))?.let { n -> "#$n" } }.distinct()
        s.row(R.string.ig_si_hashtags, tags.joinToString(" "))

        var author = MediaResolver.usernameOfUser(raw(media, "reshared_story_media_author"))
        val posts = ArrayList<String>()
        for (fm in list(media, "story_feed_media")) {
            val code = str(fm, "media_code") ?: str(fm, "code")
            val reel = str(fm, "product_type") == "clips"
            if (code != null) posts += PROFILE_URL + (if (reel) "reel/" else "p/") + code + "/"
            if (author == null) author = str(find(fm, "user"), "username")
        }
        author?.let { s.row(R.string.ig_si_shared_from, "@$it", "$PROFILE_URL$it/") }
        posts.forEach { s.row(R.string.ig_si_original_post, it, it) }

        val sponsors = usernames(raw(media, "sponsor_tags"))
        sponsors.forEach { s.row(R.string.ig_si_paid_partnership, "@$it", "$PROFILE_URL$it/") }
        if (sponsors.isEmpty() && value(media, "is_paid_partnership") == true) s.row(R.string.ig_si_paid_partnership, I18n.t(ctx, R.string.ig_si_yes))
        usernames(raw(media, "coauthor_producers")).filter { it !in sponsors }
            .forEach { s.row(R.string.ig_si_collaborators, "@$it", "$PROFILE_URL$it/") }

        val app = value(media, "story_app_attribution")
        val appName = str(app, "name") ?: str(app, "app_action_text")
        val appLink = str(app, "link") ?: str(app, "content_url")
        s.row(R.string.ig_si_posted_with, listOfNotNull(appName, appLink).joinToString("\n"), appLink)

        s.row(R.string.ig_si_alt_text, plain(value(media, "accessibility_caption")))

        val w = num(value(media, "original_width"))
        val h = num(value(media, "original_height"))
        var size = if (w != null && h != null && w > 0 && h > 0) "$w × $h" else null
        (value(media, "has_audio") as? Boolean)?.let { a ->
            val sound = I18n.t(ctx, if (a) R.string.ig_si_sound_on else R.string.ig_si_sound_off)
            size = if (size == null) sound else "$size · $sound"
        }
        s.row(R.string.ig_si_size, size)

        for (cd in list(media, "story_countdowns")) {
            val c = find(cd, "countdown_sticker") ?: cd
            s.row(R.string.ig_si_countdown, listOfNotNull(str(c, "text"), time(find(c, "end_ts"))).joinToString("\n"))
        }
        list(media, "story_questions").forEach { s.row(R.string.ig_si_question, str(it, "question")) }
        for (pr in list(media, "story_prompts")) {
            val sticker = find(pr, "prompt_sticker") ?: pr
            val lines = ArrayList<String>()
            str(sticker, "text")?.let { lines += it }
            num(find(sticker, "participant_count"))?.let { lines += NumberFormat.getIntegerInstance().format(it) + " " + I18n.t(ctx, R.string.ig_si_joined) }
            val faces = ((find(sticker, "facepile_top_participants") as? List<*>) ?: emptyList<Any>())
                .mapNotNull { str(it, "username")?.let { n -> "@$n" } }.distinct()
            if (faces.isNotEmpty()) lines += faces.joinToString(", ")
            s.row(R.string.ig_si_add_yours, lines.joinToString("\n"))
        }
        if (s.rows == 0) s.note(I18n.t(ctx, R.string.ig_si_none))
        s.show(I18n.t(ctx, R.string.ig_btn_story_info), I18n.t(ctx, R.string.ig_si_hint))
    }

    private fun music(media: Any): Pair<String, String?>? {
        for (key in listOf("story_music_stickers", "story_music_lyric_stickers", "music_metadata")) {
            val v = value(media, key) ?: continue
            find(v, "music_asset_info")?.let { licensed ->
                val title = str(licensed, "title")
                val artist = str(licensed, "display_artist")
                if (title != null) return (if (artist == null) title else "$title — $artist") to (if (artist == null) title else "$title $artist")
            }
            find(v, "original_sound_info")?.let { original ->
                val title = str(original, "original_audio_title")
                val artist = str(find(original, "ig_artist"), "username")
                if (title != null) return (if (artist == null) title else "$title — @$artist") to null
            }
        }
        return null
    }

    private fun total(options: List<*>) = options.sumOf { num(find(it, "count")) ?: 0L }

    private fun fraction(count: Long?, total: Long) = if (count == null || total <= 0) 0.0 else count.toDouble() / total

    private fun votes(count: Long?, total: Long) = when {
        count == null -> ""
        total > 0 -> "${Math.round(count * 100.0 / total)}%  ·  $count"
        else -> count.toString()
    }

    private fun votesLine(ctx: Context, n: Long) = I18n.t(ctx, R.string.ig_si_votes) + ": " + NumberFormat.getIntegerInstance().format(n)

    fun showQuiz(ctx: Context, media: Any) = guarded {
        val s = Sheet(ctx)
        for (item in list(media, "story_quizs")) {
            val quiz = find(item, "quiz_sticker") ?: item
            str(quiz, "question")?.let { s.heading(it) }
            val correct = num(find(quiz, "correct_answer"))
            val mine = num(find(quiz, "viewer_answer"))
            val options = find(quiz, "tallies") as? List<*> ?: continue
            val sum = total(options)
            options.forEachIndexed { i, o ->
                val label = str(o, "text") ?: "#${i + 1}"
                val count = num(find(o, "count"))
                val ok = correct == i.toLong()
                val picked = mine == i.toLong()
                val fill = when { ok -> s.green; picked -> s.alpha(s.accent, 0x8C); else -> s.alpha(s.text, 0x22) }
                s.bar((if (ok) "✓  " else "") + label + (if (picked) "  •" else ""), votes(count, sum), fraction(count, sum), fill, ok)
            }
            if (mine != null) s.note("•  " + I18n.t(ctx, R.string.ig_si_your_answer))
        }
        s.show(I18n.t(ctx, R.string.ig_btn_quiz_answer), null)
    }

    fun showPolls(ctx: Context, media: Any) = guarded {
        val s = Sheet(ctx)
        for (item in list(media, "story_polls")) {
            val poll = find(item, "poll_sticker") ?: item
            str(poll, "question")?.let { s.heading(it) }
            val mine = num(find(poll, "viewer_vote"))
            val options = find(poll, "tallies") as? List<*> ?: continue
            val sum = total(options)
            val best = options.maxOfOrNull { num(find(it, "count")) ?: 0L } ?: 0L
            options.forEachIndexed { i, o ->
                val label = str(o, "text") ?: "#${i + 1}"
                val count = num(find(o, "count"))
                val picked = mine == i.toLong()
                val leading = count != null && best > 0 && count == best
                val fill = if (picked) s.alpha(s.accent, 0x8C) else s.alpha(s.text, if (leading) 0x33 else 0x1C)
                s.bar((if (picked) "✓  " else "") + label, votes(count, sum), fraction(count, sum), fill, leading)
            }
            if (sum > 0) s.note(votesLine(ctx, sum))
        }
        s.show(I18n.t(ctx, R.string.ig_btn_poll_results), null)
    }

    fun showSliders(ctx: Context, media: Any) = guarded {
        val s = Sheet(ctx)
        for (item in list(media, "story_sliders")) {
            val slider = find(item, "slider_sticker") ?: item
            val emoji = str(slider, "emoji")
            s.heading(listOfNotNull(emoji, str(slider, "question")).joinToString("  "))
            val prefix = if (emoji == null) "" else "$emoji  "
            val count = num(find(slider, "slider_vote_count"))
            (find(slider, "slider_vote_average") as? Number)?.takeIf { count == null || count > 0 }?.let { avg ->
                val p = Math.round(avg.toDouble() * 100)
                s.bar(prefix + I18n.t(ctx, R.string.ig_si_average), "$p%", avg.toDouble(), s.alpha(s.text, 0x33), true)
            }
            (find(slider, "viewer_vote") as? Number)?.takeIf { it.toDouble() >= 0 }?.let { mine ->
                val p = Math.round(mine.toDouble() * 100)
                s.bar(prefix + I18n.t(ctx, R.string.ig_si_your_answer), "$p%", mine.toDouble(), s.alpha(s.accent, 0x8C), false)
            }
            if (count != null) s.note(votesLine(ctx, count))
        }
        s.show(I18n.t(ctx, R.string.ig_btn_slider_results), null)
    }

    private inline fun guarded(block: () -> Unit) {
        runCatching(block).onFailure { ModuleLog.line("(NA|StoryInfo) sheet failed: $it") }
    }
}
