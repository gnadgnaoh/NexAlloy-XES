package io.github.nexalloy.revanced.zalo.download

import android.graphics.Bitmap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Modifier

internal object ZaloJxl {
    private const val DECODER = "com.zing.zalo.zmedia.zjxl.ZJXLDecoder"
    private const val ERROR = "jxlGetErrorCode"
    private const val WIDTH = "bmPreviewWidth"
    private const val HEIGHT = "bmPreviewHeight"
    private const val QUALITY = 95
    private val CODESTREAM = byteArrayOf(0xFF.toByte(), 0x0A)
    private val CONTAINER = byteArrayOf(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20)

    fun isJxl(file: File): Boolean {
        val head = ByteArray(8)
        val read = runCatching { FileInputStream(file).use { it.read(head) } }.getOrDefault(0)
        if (read >= 2 && head[0] == CODESTREAM[0] && head[1] == CODESTREAM[1]) return true
        return read >= 8 && head.contentEquals(CONTAINER)
    }

    fun toJpeg(loader: ClassLoader, src: File, dest: File): Boolean {
        val decoder = loader.loadClass(DECODER)
        val options = loader.loadClass("$DECODER\$Options")
        val statics = decoder.declaredMethods.filter { Modifier.isStatic(it.modifiers) && !Modifier.isNative(it.modifiers) && it.returnType == Double::class.javaPrimitiveType }
        val info = statics.single { it.parameterTypes.contentEquals(arrayOf(String::class.java, options)) }
        val decode = statics.single { it.parameterTypes.contentEquals(arrayOf(String::class.java, Int::class.javaPrimitiveType, Bitmap::class.java)) }
        val error = decoder.getDeclaredMethod(ERROR, Double::class.javaPrimitiveType).apply { isAccessible = true }
        fun ok(result: Any?) = (error.invoke(null, result) as Int) == 0
        val opts = options.getDeclaredConstructor().newInstance()
        if (!ok(info.invoke(null, src.path, opts))) return false
        val width = options.getField(WIDTH).getInt(opts)
        val height = options.getField(HEIGHT).getInt(opts)
        if (width <= 0 || height <= 0) return false
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (!ok(decode.invoke(null, src.path, 1, bitmap))) return false
            return FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
