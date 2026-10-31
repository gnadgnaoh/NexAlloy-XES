package io.github.nexalloy.revanced.instagram.story

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.nexalloy.R
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.I18n
import io.github.nexalloy.revanced.instagram.download.MediaDownloadCore
import io.github.nexalloy.revanced.instagram.download.MediaResolver
import io.github.nexalloy.revanced.instagram.download.MediaSaver
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap

private const val REEL_VIEW_GROUP = "com.instagram.reels.viewer.common.ReelViewGroup"
private const val USER = "com.instagram.user.model.User"
private const val IG_ACTIVITY = "com.instagram.base.activity.IgFragmentActivity"
private const val HEADER = "reel_viewer_text_container"
private const val TITLE = "reel_viewer_title"
private val TRIES = longArrayOf(0, 250, 750)
private const val MAX_DEPTH = 8
private const val MIN_TARGET_DP = 48

val StoryMentions = patch(
    name = "See who a story mentions",
    description = "Adds a small label in a story's header showing how many accounts it mentions. " +
        "Tap it for a list and tap an account to open their profile.",
) {
    dependsOn(MediaDownloadCore)
    val binds = ::storyBindMethodsFingerprint.dexMethodList.map { it.toMethod() }
    check(binds.isNotEmpty()) { "no story bind method" }
    val itemField = ::storyPageItemFieldFingerprint.field.apply { isAccessible = true }
    val page = itemField.declaringClass
    val viewField = generateSequence<Class<*>>(page) { it.superclass }.flatMap { it.declaredFields.asSequence() }
        .single { !Modifier.isStatic(it.modifiers) && it.type.name == REEL_VIEW_GROUP }.apply { isAccessible = true }
    Mentions.mentionsGetter = ::mediaReelMentionsGetterFingerprint.method.apply { isAccessible = true }
    Mentions.fullNameGetter = runCatching { ::userFullNameGetterFingerprint.method }.getOrNull()
    Mentions.pictureGetter = runCatching { ::userProfilePicGetterFingerprint.method }.getOrNull()
    Mentions.userType = USER.findClassOrNull(classLoader)

    binds.forEach { bind ->
        bind.hookMethod {
            after { param ->
                runCatching {
                    val target = (param.args.asList() + param.thisObject).firstOrNull { page.isInstance(it) } ?: return@runCatching
                    val view = viewField.get(target) as? View ?: return@runCatching
                    Mentions.bind(view, itemField.get(target))
                }
            }
        }
    }

    val activity = IG_ACTIVITY.findClassOrNull(classLoader) ?: error("$IG_ACTIVITY not found")
    activity.declaredMethods.single { it.name == "dispatchTouchEvent" && it.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java)) }
        .hookMethod {
            before { param ->
                if (Mentions.touch(param.thisObject as Activity, param.args[0] as MotionEvent)) param.result = true
            }
        }
}

private class Person(val username: String, val fullName: String?, val picture: String?)

private class Pill(context: Context) : TextView(context) {
    var people: List<Person> = emptyList()
}

private object Mentions {
    var mentionsGetter: Method? = null
    var fullNameGetter: Method? = null
    var pictureGetter: Method? = null
    var userType: Class<*>? = null

    private val latest = WeakHashMap<View, Any>()
    private val pills: MutableSet<Pill> = Collections.newSetFromMap(WeakHashMap())
    private val pictures = LruCache<String, Bitmap>(48)
    private val userGetters = HashMap<Class<*>, Method?>()
    private var anyPill = false
    private var pressed = WeakReference<Pill>(null)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var headerId = -1
    private var titleId = -1

    fun bind(view: View, item: Any?) {
        val people = people(MediaResolver.mediaOfReelItem(item))
        if (people.isEmpty() && !anyPill) {
            latest.remove(view)
            return
        }
        val token = Any()
        latest[view] = token
        place(view, people, token, 0)
    }

    private fun place(view: View, people: List<Person>, token: Any, attempt: Int) {
        val look = Runnable {
            if (latest[view] !== token) return@Runnable
            if (show(view, people)) return@Runnable
            if (attempt + 1 < TRIES.size) place(view, people, token, attempt + 1)
        }
        if (attempt == 0) view.post(look) else view.postDelayed(look, TRIES[attempt] - TRIES[attempt - 1])
    }

    private fun people(media: Any?): List<Person> {
        val getter = mentionsGetter ?: return emptyList()
        if (media == null || !getter.declaringClass.isInstance(media)) return emptyList()
        val list = runCatching { getter.invoke(media) as? List<*> }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        return list.mapNotNull { mention ->
            val user = mention?.let(::userOf) ?: return@mapNotNull null
            val username = MediaResolver.usernameOfUser(user)?.takeIf { it.isNotBlank() && seen.add(it) } ?: return@mapNotNull null
            val name = fullNameGetter?.let { runCatching { it.invoke(user) as? String }.getOrNull() }?.takeIf { it.isNotBlank() }
            val pic = pictureGetter?.let { g -> runCatching { g.invoke(user) }.getOrNull() }
                ?.let { if (it is String) it else MediaResolver.imageUrlOf(it) }
            Person(username, name, pic)
        }
    }

    private fun userOf(mention: Any): Any? {
        val type = userType ?: return null
        val getter = synchronized(userGetters) {
            userGetters.getOrPut(mention.javaClass) {
                mention.javaClass.interfaces.flatMap { it.methods.asList() }
                    .firstOrNull { it.parameterCount == 0 && it.returnType == type }
            }
        } ?: return null
        return runCatching { getter.invoke(mention) }.getOrNull()
    }

    @SuppressLint("DiscouragedApi")
    private fun id(ctx: Context, name: String, cached: Int): Int =
        if (cached != -1) cached else ctx.resources.getIdentifier(name, "id", ctx.packageName)

    private fun header(item: View): LinearLayout? {
        headerId = id(item.context, HEADER, headerId)
        if (headerId == 0) return null
        var at: View? = item
        repeat(MAX_DEPTH) {
            val found = at?.findViewById<View>(headerId)
            if (found != null) return found as? LinearLayout
            at = at?.parent as? View
        }
        return null
    }

    private fun titleRow(header: ViewGroup): LinearLayout? {
        titleId = id(header.context, TITLE, titleId)
        if (titleId == 0) return null
        val row = header.findViewById<View>(titleId)?.parent as? LinearLayout ?: return null
        return row.takeIf { it !== header && it.orientation == LinearLayout.HORIZONTAL }
    }

    private fun pillIn(group: ViewGroup): Pill? =
        (titleRow(group)?.let { row -> (0 until row.childCount).map(row::getChildAt).firstOrNull { it is Pill } }
            ?: (0 until group.childCount).map(group::getChildAt).firstOrNull { it is Pill }) as? Pill

    private fun show(item: View, people: List<Person>): Boolean = runCatching {
        val header = header(item) ?: return people.isEmpty()
        var pill = pillIn(header)
        if (people.isEmpty()) {
            pill?.visibility = View.GONE
            return true
        }
        val ctx = header.context
        if (pill == null) {
            val into = titleRow(header) ?: header
            pill = newPill(ctx, into.orientation == LinearLayout.HORIZONTAL)
            into.addView(pill)
            anyPill = true
        }
        pill.people = people
        pill.text = if (people.size == 1) I18n.t(ctx, R.string.ig_mention_one) else I18n.t(ctx, R.string.ig_mentions_many, people.size)
        pill.visibility = View.VISIBLE
        true
    }.getOrDefault(true)

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun newPill(ctx: Context, row: Boolean): Pill = Pill(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 10), dp(ctx, 3), dp(ctx, 10), dp(ctx, 3))
        background = GradientDrawable().apply { setColor(0x59000000); cornerRadius = dp(ctx, 12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (row) { marginStart = dp(ctx, 6); gravity = Gravity.CENTER_VERTICAL } else { topMargin = dp(ctx, 4); gravity = Gravity.START }
        }
        setOnClickListener { v -> runCatching { activity(v.context)?.let { list(it, (v as Pill).people) } } }
        pills.add(this)
    }

    fun touch(activity: Activity, event: MotionEvent): Boolean = runCatching {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val down = if (pills.isEmpty()) null else pillAt(activity, event.x, event.y)
            pressed = WeakReference(down)
            if (down == null) return false
            downX = event.x; downY = event.y; moved = false
            return true
        }
        val pill = pressed.get() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (beyondSlop(pill, event.x, event.y)) moved = true
            MotionEvent.ACTION_POINTER_DOWN -> moved = true
            MotionEvent.ACTION_UP -> {
                pressed = WeakReference(null)
                if (!moved && !beyondSlop(pill, event.x, event.y) && pill.isShown) pill.performClick()
            }
            MotionEvent.ACTION_CANCEL -> pressed = WeakReference(null)
        }
        true
    }.getOrElse { pressed = WeakReference(null); false }

    private fun pillAt(activity: Activity, x: Float, y: Float): Pill? {
        val decor = activity.window?.peekDecorView() ?: return null
        for (pill in pills.toList()) {
            if (!pill.isShown || pill.width == 0 || pill.rootView !== decor) continue
            if (holds(pill, x, y, 0, 0)) return pill
            val target = dp(pill.context, MIN_TARGET_DP)
            val padX = maxOf(0, target - pill.width) / 2
            val padY = maxOf(0, target - pill.height) / 2
            val parent = pill.parent as? ViewGroup ?: continue
            if (!holds(pill, x, y, padX, padY) || !holds(parent, x, y, 0, 0)) continue
            val onOther = (0 until parent.childCount).map(parent::getChildAt)
                .any { it !== pill && it.visibility == View.VISIBLE && holds(it, x, y, 0, 0) }
            if (!onOther) return pill
        }
        return null
    }

    private fun holds(view: View, x: Float, y: Float, padX: Int, padY: Int): Boolean {
        val at = IntArray(2)
        view.getLocationInWindow(at)
        return x >= at[0] - padX && x < at[0] + view.width + padX && y >= at[1] - padY && y < at[1] + view.height + padY
    }

    private fun beyondSlop(pill: View, x: Float, y: Float): Boolean {
        val slop = ViewConfiguration.get(pill.context).scaledTouchSlop
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy > slop.toFloat() * slop
    }

    private fun activity(ctx: Context?): Activity? {
        var at = ctx
        while (at is ContextWrapper) {
            if (at is Activity) return at
            at = at.baseContext
        }
        return null
    }

    private fun list(activity: Activity, people: List<Person>) {
        if (people.isEmpty()) return
        val builder = AlertDialog.Builder(activity)
            .setTitle(I18n.t(activity, R.string.ig_mentions_title))
            .setNegativeButton(I18n.t(activity, R.string.ig_close), null)
        val themed = builder.context
        val dialog = builder.create()
        val rows = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(themed, 8), 0, dp(themed, 8)) }
        people.forEach { person ->
            rows.addView(row(themed, person) {
                dialog.dismiss()
                val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/" + Uri.encode(person.username) + "/"))
                open.setPackage(activity.packageName)
                activity.startActivity(open)
            })
        }
        dialog.setView(ScrollView(themed).apply { addView(rows) })
        dialog.show()
    }

    private fun row(ctx: Context, person: Person, open: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64)
            setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), dp(ctx, 8))
            val attrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = attrs.getDrawable(0)
            attrs.recycle()
            isClickable = true
            setOnClickListener { runCatching { open() } }
        }
        val size = dp(ctx, 40)
        val picture = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(ctx, 16) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33808080) })
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }
        row.addView(picture)
        person.picture?.let { load(picture, it, size) }
        val names = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        person.fullName?.let { full ->
            names.addView(TextView(ctx).apply {
                text = full; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD
                isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            })
        }
        names.addView(TextView(ctx).apply {
            text = "@" + person.username
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (person.fullName != null) 14f else 16f)
            if (person.fullName != null) alpha = 0.7f
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(names)
        return row
    }

    private fun load(view: ImageView, url: String, size: Int) {
        pictures.get(url)?.let { view.setImageBitmap(it); return }
        MediaSaver.executor.submit {
            val bitmap = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                try { conn.inputStream.use { BitmapFactory.decodeStream(it) } } finally { conn.disconnect() }
            }.getOrNull() ?: return@submit
            val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
            pictures.put(url, scaled)
            view.post { view.setImageBitmap(scaled) }
        }
    }
}
