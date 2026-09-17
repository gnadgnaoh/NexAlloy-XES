package io.github.nexalloy

import io.github.nexalloy.morphe.Fingerprint
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.ArgumentsSource
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.system.measureTimeMillis

@ParameterizedClass
@ArgumentsSource(FilePathArgumentsProvider::class)
class FingerprintsKtTest(val apkPath: Path) {
    val context = ApkContext(apkPath.toString())
    val dexkit: DexKitBridge = context.dexkit
    val appVersion: AppVersion = context.appVersion

    // region Test runner

    private val errors = mutableListOf<Throwable>()

    /**
     * Run a named fingerprint test, printing result and timing.
     */
    private fun runTest(name: String, block: DexKitBridge.() -> Any?) {
        print("$name: ")
        try {
            val time = measureTimeMillis {
                val value = block(dexkit)
                if (value is List<*>) {
                    assertTrue(value.isNotEmpty())
                    print(value.joinToString(", "))
                } else {
                    print("$value")
                }
            }
            if (time > 20) print(", slow match: ${time}ms")
            println()
        } catch (e: Throwable) {
            println()
            errors.add(e)
            System.err.println(e.stackTraceToString())
        }
    }

    /**
     * Check @SkipTest / @RequireAppVersion / @TargetApp annotations.
     * Returns true if the test should be skipped.
     */
    private fun shouldSkip(
        name: String,
        annotations: Array<Annotation>,
        app: String,
    ): Boolean {
        if (annotations.any { it is SkipTest }) return true

        annotations.filterIsInstance<TargetApp>().firstOrNull()?.let {
            if (it.app != app) return true
        }

        annotations.filterIsInstance<RequireAppVersion>().firstOrNull()?.let { anno ->
            try {
                match(appVersion, anno.minVersion, anno.maxVersion)
            } catch (e: VersionConstraintFailedException) {
                print("$name: ")
                System.out.flush()
                System.err.println("Skipping: ${e.message}")
                return true
            }
        }

        return false
    }

    // endregion

    // region Fingerprint discovery

    /**
     * Test all fingerprints for a given package:
     * - val properties returning [FindFunc] on the FingerprintsKt class (if it exists)
     * - object : Fingerprint(...) declarations in the same package
     */
    fun testFingerprints(app: String, packageName: String) {
        context.setupCurrentThread()
        errors.clear()

        val classLoader = Thread.currentThread().contextClassLoader!!

        // Test val properties on FingerprintsKt (may not exist if all fingerprints are objects).
        val fingerprintsKtClass = try {
            classLoader.loadClass("${packageName}.FingerprintsKt")
        } catch (_: ClassNotFoundException) {
            null
        }
        if (fingerprintsKtClass != null) {
            testValProperties(app, fingerprintsKtClass)
        }

        // Test object : Fingerprint(...) declarations.
        testFingerprintObjects(app, packageName, classLoader)

        if (errors.isNotEmpty()) throw AssertionError()
    }

    /**
     * Test top-level val properties whose getter returns [FindFunc].
     */
    private fun testValProperties(app: String, clazz: Class<*>) {
        clazz.methods.asSequence()
            .filter { it.isStatic }
            .sortedBy { it.name }
            .forEach { method ->
                val name = method.name.drop(3) // drop "get" prefix
                if (shouldSkip(name, method.annotations, app)) return@forEach

                val func = method(null) as? FindFunc ?: return@forEach
                runTest(name) { func() }
            }
    }

    /**
     * Test `object : Fingerprint(...)` declarations in [packageName].
     */
    private fun testFingerprintObjects(app: String, packageName: String, classLoader: ClassLoader) {
        for (className in findClassNamesInPackage(packageName, classLoader).sorted()) {
            val cls = try {
                classLoader.loadClass(className)
            } catch (_: ClassNotFoundException) {
                continue
            }

            if (!Fingerprint::class.java.isAssignableFrom(cls)) continue
            val instance = try {
                cls.kotlin.objectInstance as? Fingerprint ?: continue
            } catch (_: IllegalAccessException) {
                // Private object declarations cannot be accessed via reflection.
                continue
            }

            val name = cls.simpleName
            if (shouldSkip(name, cls.annotations, app)) continue

            runTest(name) {
                instance.run()
            }
        }
    }

    /**
     * Find all class names in [packageName],
     * handling both directory-based and JAR-based classpaths.
     */
    private fun findClassNamesInPackage(
        packageName: String,
        classLoader: ClassLoader,
    ): Set<String> {
        val packagePath = packageName.replace('.', '/')
        val result = mutableSetOf<String>()

        // Try to find the package directory via classpath resources.
        val urls = classLoader.getResources(packagePath)
        for (url in urls.asSequence()) {
            when (url.protocol) {
                "file" -> {
                    val dir = File(url.toURI())
                    if (!dir.isDirectory) continue
                    dir.listFiles { f -> f.isClassFile }
                        ?.forEach { result.add("$packageName.${it.nameWithoutExtension}") }
                }
            }
        }

        // Also scan JAR files on the classpath.
        if (result.isEmpty()) {
            val classpath = System.getProperty("java.class.path") ?: return result
            for (entry in classpath.split(File.pathSeparator)) {
                val file = File(entry)
                if (!file.isFile || !file.name.endsWith(".jar")) continue
                try {
                    JarFile(file).use { jar ->
                        jar.entries().asSequence()
                            .map { it.name }
                            .filter { it.startsWith("$packagePath/") && it.endsWith(".class") }
                            .filter { !it.contains('$') }
                            .filter { it.removePrefix("$packagePath/").indexOf('/') < 0 }
                            .forEach { result.add(it.removeSuffix(".class").replace('/', '.')) }
                    }
                } catch (_: Exception) {
                    // Skip unreadable JARs.
                }
            }
        }

        return result
    }

    private val File.isClassFile get() = name.endsWith(".class") && !name.contains('$')

    // endregion

    // region Test factory

    @TestFactory
    fun fingerprintTest(): Iterator<DynamicTest> = sequence {
        val app = when {
            apkPath.name.startsWith("com.google.android.youtube") -> "youtube"
            apkPath.name.startsWith("com.google.android.apps.youtube.music") -> "music"
            apkPath.name.startsWith("com.reddit.frontpage") -> "reddit"
            else -> return@sequence
        }

        fun findFingerprintPackages(rootPackage:String): MutableList<String> =
            Files.walk(Path(rootPackage))
                .filter { Files.isRegularFile(it) && it.fileName.toString() == "Fingerprints.kt" }
                .map {
                    // drop src/main/java, drop filename → package name
                    it.parent.invariantSeparatorsPathString.split("/").drop(3)
                        .joinToString(".")
                }.toList().toMutableList()

        val packageNames =
            findFingerprintPackages("src/main/java/io/github/nexalloy/morphe/$app")

        // Add shared fingerprints packages.
        if (app == "youtube"  || app == "music"){
            packageNames.addAll(findFingerprintPackages("src/main/java/io/github/nexalloy/morphe/shared"))
        }

        packageNames.distinct().forEach { packageName ->
            var category = packageName.split(".").drop(5).joinToString(".")
            if (category == "") category = "shared"
            yield(DynamicTest.dynamicTest(category) { testFingerprints(app, packageName) })
        }
    }.iterator()

    // endregion
}
