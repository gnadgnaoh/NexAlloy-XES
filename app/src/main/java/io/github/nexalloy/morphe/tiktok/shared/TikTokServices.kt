package io.github.nexalloy.morphe.tiktok.shared

import android.content.Context
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.callStaticMethodOrNull
import io.github.nexalloy.findClassOrNull

internal object TikTokServices {
    private const val SERVICE_MANAGER_CLASS = "com.ss.android.ugc.aweme.framework.services.ServiceManager"
    private const val MAIN_SERVICE_CLASS = "com.ss.android.ugc.aweme.services.IMainService"
    private const val ACCOUNT_USER_SERVICE_CLASS = "com.ss.android.ugc.aweme.IAccountUserService"

    @Volatile
    private var classLoader: ClassLoader? = null

    fun init(classLoader: ClassLoader) {
        this.classLoader = classLoader
    }

    private fun service(interfaceName: String): Any? {
        val loader = classLoader ?: return null
        val manager = SERVICE_MANAGER_CLASS.findClassOrNull(loader)?.callStaticMethodOrNull("get") ?: return null
        val type = interfaceName.findClassOrNull(loader) ?: return null
        return manager.callMethodOrNull("getService", type)
    }

    /** False when the account service cannot be reached, so callers fail towards TikTok's behavior. */
    fun isLoggedIn(): Boolean = service(ACCOUNT_USER_SERVICE_CLASS)?.callMethodOrNull("isLogin") == true

    /** TikTok's own "open in system browser" (chooser, fallbacks, intent flags). */
    fun openSystemBrowser(context: Context, url: String): Boolean =
        service(MAIN_SERVICE_CLASS)?.callMethodOrNull("openSystemBrowser", context, url) == true
}
