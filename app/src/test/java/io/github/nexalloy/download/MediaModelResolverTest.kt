package io.github.nexalloy.revanced.instagram.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MediaModelResolverTest {

    @Test
    fun resolvesLiveTreeWhenLegacyInterfaceIsMissing() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            @Throws(ClassNotFoundException::class)
            override fun loadClass(name: String): Class<*> {
                if (MediaModelResolver.MUTABLE_DICT_CLASS == name) {
                    throw ClassNotFoundException(name)
                }
                if (MediaModelResolver.LIVE_TREE_DICT_CLASS == name) {
                    return ModernDict::class.java
                }
                return super.loadClass(name)
            }
        }

        val result = MediaModelResolver.resolve(loader)

        assertNull(result.mutableDictClass)
        assertEquals(ModernDict::class.java, result.liveTreeDictClass)
        assertEquals(1, result.listCandidates.size)
        assertEquals("videoVersions", result.listCandidates[0].name)
    }

    @Test
    fun findsConcreteDictionaryStoredInObjectTypedField() {
        val dict = ModernDict()
        val media = MediaContainer(dict)
        val model = MediaModelResolver.Result(null, ModernDict::class.java, listOf())

        val found = MediaModelResolver.findDictionary(media, model, 2)

        assertNotNull(found)
        assertSame(dict, found)
    }

    @Test
    fun findsDictionaryExposedOnlyByModelGetter() {
        val media = MethodOnlyContainer()
        val model = MediaModelResolver.Result(null, ModernDict::class.java, listOf())

        val found = MediaModelResolver.findDictionary(media, model, 2)

        assertNotNull(found)
        assertEquals(ModernDict::class.java, found!!.javaClass)
    }

    @Test
    fun findsDictionaryInsideIterableWrapper() {
        val dict = ModernDict()

        val found = MediaModelResolver.findObjectOfType(listOf(dict), ModernDict::class.java, 2)

        assertSame(dict, found)
    }

    class MediaContainer(@Suppress("unused") private val backing: Any)

    class ModernDict {
        fun videoVersions(): List<String> = listOf("video")

        fun unrelated(): String = "ignored"
    }

    class MethodOnlyContainer {
        fun getExtendedData(): ModernDict = ModernDict()
    }
}
