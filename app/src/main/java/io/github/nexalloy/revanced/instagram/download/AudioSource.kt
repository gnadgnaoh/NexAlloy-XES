package io.github.nexalloy.revanced.instagram.download

import io.github.nexalloy.revanced.shared.media.Dash
import java.lang.reflect.Method

internal object AudioSource {

    class Audio(val url: String, val fromMs: Long, val lengthMs: Long)

    private const val MUSIC_INFO = "com.instagram.api.schemas.MusicInfo"
    private const val TRACK_DATA = "com.instagram.api.schemas.TrackData"
    private const val MUSIC_CONSUMPTION = "com.instagram.music.common.model.MusicConsumptionModel"

    private var holders: List<Method> = emptyList()
    private var musicInfo: Class<*>? = null
    private var trackGetter: Method? = null
    private var consumptionGetter: Method? = null
    private var urlGetter: Method? = null
    private var startGetter: Method? = null
    private var lengthGetter: Method? = null

    fun bind(cl: ClassLoader, holders: List<Method>, track: Method, consumption: Method, url: Method, start: Method, length: Method) {
        fun viaInterface(name: String, m: Method) = cl.loadClass(name).getMethod(m.name)
        this.holders = holders.onEach { it.isAccessible = true }
        musicInfo = cl.loadClass(MUSIC_INFO)
        trackGetter = viaInterface(MUSIC_INFO, track)
        consumptionGetter = viaInterface(MUSIC_INFO, consumption)
        urlGetter = viaInterface(TRACK_DATA, url)
        startGetter = viaInterface(MUSIC_CONSUMPTION, start)
        lengthGetter = viaInterface(MUSIC_CONSUMPTION, length)
    }

    fun of(media: Any?, parent: Any? = null): Audio? {
        media ?: return null
        if (MediaResolver.isVideo(media) || MediaResolver.videoUrl(media) != null) {
            val track = MediaResolver.dashManifest(media)?.let { Dash.bestAudio(Dash.parse(it)) } ?: return null
            return Audio(track.url, -1, -1)
        }
        return music(media) ?: parent?.let(::music)
    }

    private fun music(media: Any): Audio? {
        val info = holders.firstNotNullOfOrNull { holder ->
            val value = runCatching { holder.invoke(media) }.getOrNull() ?: return@firstNotNullOfOrNull null
            musicInfoOf(value)
        } ?: return null
        val track = runCatching { trackGetter?.invoke(info) }.getOrNull() ?: return null
        val url = runCatching { urlGetter?.invoke(track) as? String }.getOrNull()?.takeIf { it.startsWith("http") } ?: return null
        val part = runCatching { consumptionGetter?.invoke(info) }.getOrNull()
        val from = runCatching { startGetter?.invoke(part) as? Int }.getOrNull()
        val length = runCatching { lengthGetter?.invoke(part) as? Int }.getOrNull()
        return Audio(url, from?.toLong() ?: -1, length?.toLong() ?: -1)
    }

    private fun musicInfoOf(holder: Any): Any? {
        val type = musicInfo ?: return null
        if (type.isInstance(holder)) return holder
        val getter = holder.javaClass.methods.singleOrNull { it.parameterCount == 0 && it.returnType == type } ?: return null
        return runCatching { getter.invoke(holder) }.getOrNull()
    }
}
