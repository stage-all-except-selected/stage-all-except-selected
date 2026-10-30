package io.github.yendrrek.stageotherfiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile

/**
 * No compiled class of this plugin may call a **Kotlin default-argument synthetic** of a
 * `com.intellij` class: the one mistake that compiles cleanly, passes every test on the baseline
 * IDE, and throws `NoSuchMethodError` on a newer one.
 *
 * Kotlin compiles a call that omits a defaulted parameter into a call to a generated member: a
 * `foo$default(...)` static, or a constructor carrying every parameter plus a bitmask and a
 * `DefaultConstructorMarker`. Both descriptors list every parameter the callee declares, so either
 * changes shape when the platform adds one, and the call is linked against the user's IDE.
 *
 * Scoped to platform classes in an `impl`, `internal` or `ex` package, and to all of `git4idea`,
 * which carry no compatibility promise. Stable platform API, the Kotlin stdlib and `kotlinx.coroutines` are left to
 * `verifyPlugin`, which checks the whole IDE matrix. Fix an offender by passing every argument
 * explicitly, or by building the value in Java. Reflection hides the call from the Verifier.
 */
class PlatformDefaultArgumentCallsTest {

    @Test
    fun `no class calls a platform Kotlin default-argument synthetic`() {
        val classes = pluginClasses()
        // A guard that silently found nothing to guard would be worse than none: where the plugin's
        // own classes sit on a test JVM's classpath is Gradle's business, not ours, and it is
        // exactly the sort of thing a build-plugin upgrade moves.
        assertTrue("expected the plugin's compiled classes on the test classpath, found none", classes.isNotEmpty())

        val offenders = classes.flatMap { compiled ->
            platformSyntheticCallsIn(compiled).map { "${compiled.name}: $it" }
        }
        assertEquals(
            "these call a platform default-argument synthetic, whose descriptor is not stable across " +
                "IDE versions — pass every argument explicitly, or build the value in Java",
            emptyList<String>(),
            offenders.sorted(),
        )
    }

    private class CompiledClass(val name: String, val bytes: ByteArray)

    /**
     * Every compiled class of this plugin, wherever the test JVM was handed them.
     *
     * Both shapes are read, because both occur and neither is ours to choose: Gradle puts the test
     * source set's own output on the classpath as directories, while `main` arrives as the *built
     * plugin jar* (`intellijPlatform` assembles one for the test sandbox) — which is the better
     * artefact of the two anyway, being the one that reaches users. The search is confined to
     * classpath entries inside the project directory so that this does not walk the several hundred
     * jars of the IDE it compiles against looking for a package they cannot hold.
     */
    private fun pluginClasses(): List<CompiledClass> {
        val projectDir = File(System.getProperty("user.dir")).canonicalFile
        return System.getProperty("java.class.path").split(File.pathSeparator)
            .map { File(it).canonicalFile }
            .filter { it.startsWith(projectDir) }
            .flatMap { entry ->
                when {
                    entry.isDirectory -> classesUnder(entry)
                    entry.name.endsWith(".jar") -> classesIn(entry)
                    else -> emptyList()
                }
            }
            .distinctBy { it.name }
    }

    private fun classesUnder(root: File): List<CompiledClass> {
        val packageRoot = File(root, PACKAGE_ROOT)
        if (!packageRoot.isDirectory) return emptyList()
        return Files.walk(packageRoot.toPath()).use { paths ->
            paths.toList()
                .map { it.toFile() }
                .filter { it.isFile && it.name.endsWith(CLASS_SUFFIX) }
                .map { CompiledClass(it.relativeTo(root).path.replace(File.separatorChar, '/'), it.readBytes()) }
        }
    }

    private fun classesIn(jar: File): List<CompiledClass> = JarFile(jar).use { archive ->
        archive.entries().toList()
            .filter { it.name.startsWith("$PACKAGE_ROOT/") && it.name.endsWith(CLASS_SUFFIX) }
            .map { entry -> CompiledClass(entry.name, archive.getInputStream(entry).use { it.readBytes() }) }
    }

    /**
     * The synthetic `com.intellij` members [compiled] calls, each as `owner.name` and descriptor.
     *
     * Read straight out of the constant pool rather than through a bytecode library: the whole
     * question is which method references a class file holds, the pool is the only part of the format
     * that answers it, and a dependency taken on for that would be one more thing to keep current
     * than the parser below.
     */
    private fun platformSyntheticCallsIn(compiled: CompiledClass): List<String> {
        val pool = constantPool(compiled)
        return pool.methodRefs()
            .map { ref ->
                Triple(
                    pool.utf8(pool.classNameIndex(ref.classIndex)),
                    pool.utf8(ref.nameIndex),
                    pool.utf8(ref.descriptorIndex),
                )
            }
            .filter { (owner, name, descriptor) ->
                isUnstablePlatformClass(owner) &&
                    (name.endsWith("\$default") || descriptor.contains(DEFAULT_CONSTRUCTOR_MARKER))
            }
            .map { (owner, name, descriptor) -> "$owner.$name$descriptor" }
            .distinct()
    }

    /**
     * Whether [owner] is a platform class the platform reserves the right to change under us —
     * which is what makes a synthetic call into it a linkage error waiting for an IDE upgrade.
     *
     * Read off the package rather than off `@ApiStatus.Internal`, which is a *class file* annotation
     * this test would have to resolve against a live IDE to see. The three segments below are the
     * platform's own long-standing spelling of "not API" and cover the case that caused this test to
     * exist; the stable API they leave out is `verifyPlugin`'s job, as the class doc explains.
     * Git4Idea publishes no API at all, so every class in it counts.
     */
    private fun isUnstablePlatformClass(owner: String): Boolean =
        owner.startsWith(GIT4IDEA_PACKAGE) ||
            (owner.startsWith(PLATFORM_PACKAGE) && UNSTABLE_SEGMENTS.any { owner.contains(it) })

    private class MethodRef(val classIndex: Int, val nameIndex: Int, val descriptorIndex: Int)

    /** A parsed pool, holding only the entry kinds this test reads: UTF-8, `Class` and method refs. */
    private class Pool(private val constants: Map<Int, Any>) {
        fun methodRefs(): List<MethodRef> = constants.values.filterIsInstance<MethodRef>()

        fun utf8(index: Int): String = constants[index] as? String ?: ""

        /** A `Class` entry holds the index of the UTF-8 that spells its name. */
        fun classNameIndex(index: Int): Int = constants[index] as? Int ?: 0
    }

    /**
     * The constant pool of [compiled], parsed far enough to resolve a method reference to its
     * owner, name and descriptor — everything after the pool is left unread.
     *
     * A `Methodref` holds a class index and a `NameAndType` index, and either may point *forward*,
     * so the references are folded flat in a second pass once the whole pool is in hand.
     */
    private fun constantPool(compiled: CompiledClass): Pool {
        DataInputStream(ByteArrayInputStream(compiled.bytes)).use { input ->
            require(input.readInt() == CLASS_FILE_MAGIC) { "not a class file: ${compiled.name}" }
            input.skipNBytes(4) // minor and major version
            val count = input.readUnsignedShort()
            val entries = mutableMapOf<Int, Any>()
            val nameAndTypes = mutableMapOf<Int, Pair<Int, Int>>()
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    TAG_UTF8 -> entries[index] = input.readUTF()
                    TAG_INTEGER, TAG_FLOAT -> input.skipNBytes(4)
                    // Long and Double take two pool slots each — the format's one real trap.
                    TAG_LONG, TAG_DOUBLE -> { input.skipNBytes(8); index++ }
                    TAG_CLASS -> entries[index] = input.readUnsignedShort()
                    TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> input.skipNBytes(2)
                    TAG_FIELDREF, TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> input.skipNBytes(4)
                    TAG_METHODREF, TAG_INTERFACE_METHODREF ->
                        entries[index] = MethodRef(input.readUnsignedShort(), input.readUnsignedShort(), 0)
                    TAG_NAME_AND_TYPE -> nameAndTypes[index] = input.readUnsignedShort() to input.readUnsignedShort()
                    TAG_METHOD_HANDLE -> input.skipNBytes(3)
                    else -> error("unknown constant pool tag $tag in ${compiled.name}")
                }
                index++
            }
            return Pool(
                entries.mapValues { (_, value) ->
                    if (value !is MethodRef) {
                        value
                    } else {
                        val (name, descriptor) = nameAndTypes[value.nameIndex] ?: (0 to 0)
                        MethodRef(value.classIndex, name, descriptor)
                    }
                },
            )
        }
    }

    private companion object {
        const val PACKAGE_ROOT = "io/github/yendrrek/stageotherfiles" // the plugin's root package, slash-separated
        const val PLATFORM_PACKAGE = "com/intellij/"
        const val GIT4IDEA_PACKAGE = "git4idea/"
        val UNSTABLE_SEGMENTS = listOf("/impl/", "/internal/", "/ex/")
        const val CLASS_SUFFIX = ".class"
        const val DEFAULT_CONSTRUCTOR_MARKER = "Lkotlin/jvm/internal/DefaultConstructorMarker;"
        const val CLASS_FILE_MAGIC = -0x35014542 // 0xCAFEBABE, as a signed int

        const val TAG_UTF8 = 1
        const val TAG_INTEGER = 3
        const val TAG_FLOAT = 4
        const val TAG_LONG = 5
        const val TAG_DOUBLE = 6
        const val TAG_CLASS = 7
        const val TAG_STRING = 8
        const val TAG_FIELDREF = 9
        const val TAG_METHODREF = 10
        const val TAG_INTERFACE_METHODREF = 11
        const val TAG_NAME_AND_TYPE = 12
        const val TAG_METHOD_HANDLE = 15
        const val TAG_METHOD_TYPE = 16
        const val TAG_DYNAMIC = 17
        const val TAG_INVOKE_DYNAMIC = 18
        const val TAG_MODULE = 19
        const val TAG_PACKAGE = 20
    }
}
