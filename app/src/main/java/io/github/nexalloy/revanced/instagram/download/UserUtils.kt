package io.github.nexalloy.revanced.instagram.download

import java.lang.reflect.Method

object UserUtils {

    @JvmField var userUsernameGetter: Method? = null

    fun callUsernameGetter(user: Any?): String? {
        if (user == null) return null

        userUsernameGetter?.let { getter ->
            try {
                val r = getter.invoke(user)
                if (r is String && isValidUsername(r)) return r
            } catch (ignored: Throwable) {
            }
        }

        for (m in user.javaClass.declaredMethods) {
            if (m.parameterCount != 0 || m.returnType != String::class.java) continue
            try {
                m.isAccessible = true
                val r = m.invoke(user)
                if (r is String && isValidUsername(r)) {
                    userUsernameGetter = m
                    return r
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    fun isValidUsername(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        return s.matches(Regex("^[a-z0-9._]{2,30}$"))
    }
}
