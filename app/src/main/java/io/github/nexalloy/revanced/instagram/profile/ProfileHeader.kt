package io.github.nexalloy.revanced.instagram.profile

import android.view.View
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

internal object ProfileHeader {
    private const val VIEW_MODEL = "com.instagram.profile.fragment.UserDetailViewModel"
    private const val USER = "com.instagram.user.model.User"
    private const val SESSION = "com.instagram.common.session.UserSession"

    class Bound(val header: View, val user: Any, val isViewer: Boolean)

    private val listeners = CopyOnWriteArrayList<(Bound) -> Unit>()

    @Volatile
    private var last: WeakReference<Any>? = null

    @Volatile var biographyGetter: Method? = null
    @Volatile var hdPictureGetter: Method? = null

    val lastUser: Any? get() = last?.get()

    fun onBound(listener: (Bound) -> Unit) {
        listeners += listener
    }

    fun valueOfType(host: Any?, typeName: String): Any? {
        var type: Class<*>? = host?.javaClass ?: return null
        while (type != null && type != Any::class.java) {
            for (f in type.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.name != typeName) continue
                return runCatching { f.isAccessible = true; f.get(host) }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun userId(obj: Any?): String? =
        runCatching { obj?.javaClass?.getMethod("getUserId")?.invoke(obj) as? String }.getOrNull()

    fun bound(args: Array<Any?>) {
        val header = args.firstOrNull { it is View } as? View ?: return
        val vm = args.firstNotNullOfOrNull { valueOfType(it, VIEW_MODEL) } ?: return
        val user = valueOfType(vm, USER) ?: return
        last = WeakReference(user)
        val viewerId = userId(valueOfType(vm, SESSION))
        val bound = Bound(header, user, viewerId != null && viewerId == userId(user))
        listeners.forEach { runCatching { it(bound) } }
    }
}

internal val ProfileHeaderHook = patch(name = "<ProfileHeaderHook>") {
    val binder = ::profileHeaderBinderFingerprint.method
    ProfileHeader.biographyGetter = runCatching { ::userBiographyGetterFingerprint.method }.getOrNull()
    ProfileHeader.hdPictureGetter = runCatching { ::userHdProfilePicGetterFingerprint.method }.getOrNull()
    binder.hookMethod { after { param -> runCatching { ProfileHeader.bound(param.args) } } }
}
