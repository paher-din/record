package com.paherdin.record.core.data

import kotlin.Metadata
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmVariance
import kotlin.metadata.Visibility
import kotlin.metadata.isData
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.kind
import kotlin.metadata.visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Criterion 7b (full public-signature allowlist). Scans only `:core:data`'s
 * own declarations: the classpath element containing `RecordDatabase.class`
 * is the module's main classes dir, which contains only `:core:data` classes
 * (`:core:model` types appear in signatures by qualified name and are not
 * scanned). Reflection is used solely to fetch the `@Metadata` annotation;
 * all visibility and signature decisions come from
 * `KotlinClassMetadata.readStrict` (the official documented entry, reading
 * `kmClass`/`kmPackage` per the returned metadata kind). Classes without
 * `@Metadata` fail by default — the only exclusion is the pinned
 * Room-generated FQCN set, never a wildcard. The public declarations must
 * match `api/expected-surface.txt` exactly, in both directions. Synthetic
 * metadata kinds (compiler-generated, e.g. enum `when` mappings) carry no API
 * surface and are skipped; they are not design decisions.
 */
class ApiSurfaceTest {

    companion object {
        /**
         * The only no-metadata/public-class exclusion: pinned, never a
         * wildcard. Nested classes of a pinned FQCN (e.g.
         * `RecordDatabase_Impl$Companion`, which Room generates with
         * `getRequiredConverters`) are covered by the same pin.
         */
        private val PINNED_EXCLUSIONS = setOf(
            "com.paherdin.record.core.data.room.RecordDatabase_Impl",
            "com.paherdin.record.core.data.room.RecordDao_Impl",
        )

        private fun isPinned(fqcn: String): Boolean =
            fqcn in PINNED_EXCLUSIONS || PINNED_EXCLUSIONS.any { fqcn.startsWith("$it$") }

        /** Must be `internal` per the compiled Kotlin metadata. */
        private val INTERNAL_CLASSES = setOf(
            "com.paherdin.record.core.data.room.EntityRef",
            "com.paherdin.record.core.data.room.Transaction",
            "com.paherdin.record.core.data.room.Account",
            "com.paherdin.record.core.data.room.AccountEntry",
            "com.paherdin.record.core.data.room.Note",
            "com.paherdin.record.core.data.room.TransactionNoteRelation",
            "com.paherdin.record.core.data.room.RecordDao",
            "com.paherdin.record.core.data.room.RecordDatabase",
            "com.paherdin.record.core.data.XorInvariantValidator",
        )

        private val ALLOWLIST_FILE = File("api/expected-surface.txt")

        private val DUMP = System.getProperty("apiSurface.dump") == "true"
    }

    /**
     * Locates the module's own classes — the classpath element containing
     * `RecordDatabase.class` — and materializes it as a directory: the classes
     * dir when AGP exposes one, otherwise the module's classes jar extracted
     * to a temp dir. Returns (root, list of .class files under
     * `com/paherdin/record/core/data`).
     */
    private fun moduleClassFiles(): Pair<File, List<File>> {
        val targetEntry = "com/paherdin/record/core/data/room/RecordDatabase.class"
        val element = System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map { File(it) }
            .firstOrNull { file ->
                (file.isDirectory && File(file, targetEntry).isFile) ||
                    (file.isFile && file.name.endsWith(".jar") &&
                        java.util.zip.ZipFile(file).use { zip -> zip.getEntry(targetEntry) != null })
            } ?: error("module classes element (RecordDatabase.class) not found on the test classpath")
        val root = File(element.toURI()).let { file ->
            if (file.isDirectory) file else {
                val tempDir = createTempDir("apiSurfaceModuleClasses")
                java.util.zip.ZipFile(file).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (!entry.isDirectory) {
                            val target = File(tempDir, entry.name)
                            target.parentFile.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                }
                tempDir
            }
        }
        val subtree = File(root, "com/paherdin/record/core/data")
        check(subtree.isDirectory) { "expected module classes subtree missing: $subtree" }
        return root to subtree.walkTopDown().filter { it.extension == "class" }.toList()
    }

    /** Scans the module classes; returns (binary FQCN, kmClass) for every class with metadata. */
    private fun collect(): List<Pair<String, KmClass>> {
        val (root, classFiles) = moduleClassFiles()
        val result = mutableListOf<Pair<String, KmClass>>()
        classFiles.forEach { classFile ->
            val fqcn = classFile.relativeTo(root).path
                .removeSuffix(".class")
                .replace(File.separatorChar, '.')
            if (isPinned(fqcn)) return@forEach
            val clazz = Class.forName(fqcn)
            val metadata = clazz.getAnnotation(Metadata::class.java)
                ?: error("no @Metadata on $fqcn — unexpected surface; only the pinned Room-generated FQCN set is exempt")
            when (val km = KotlinClassMetadata.readStrict(metadata)) {
                is KotlinClassMetadata.Class -> result += fqcn to km.kmClass
                is KotlinClassMetadata.FileFacade -> {
                    val publicTopLevel = km.kmPackage.functions.filter { it.visibility == Visibility.PUBLIC }
                    check(publicTopLevel.isEmpty()) { "public top-level functions in $fqcn: ${publicTopLevel.map { it.name }}" }
                }
                is KotlinClassMetadata.SyntheticClass -> Unit // compiler-generated; no API surface
                else -> error("unexpected metadata kind for $fqcn: ${km::class.simpleName}")
            }
        }
        return result
    }

    @Test
    fun internalClassesAreInternalPerMetadata() {
        val scanned = collect().map { it.first }.toSet()
        val internalFqns = collect().filter { (_, k) -> k.visibility == Visibility.INTERNAL }.map { it.first }.toSet()
        INTERNAL_CLASSES.forEach { fqcn ->
            check(fqcn in scanned) { "$fqcn missing from the scan" }
            check(fqcn in internalFqns) { "$fqcn is not internal per compiled metadata" }
        }
    }

    @Test
    fun publicSurfaceMatchesAllowlistExactly() {
        val actual = collect().flatMap { (_, k) -> renderClass(k) }.sorted()
        if (DUMP) {
            println("=== api/expected-surface.txt (${actual.size} lines) ===")
            actual.forEach { println(it) }
            return
        }
        val expected = ALLOWLIST_FILE.readLines().map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        assertEquals("public declarations differ from api/expected-surface.txt (both directions)", expected, actual)
        // Defense in depth: no projection type or projection-write entry anywhere in the surface.
        for (banned in listOf("SearchDocument", "Timeline")) {
            check(expected.none { it.contains(banned) }) { "projection surface '$banned' present in the allowlist" }
        }
    }

    // ---- normalization ----

    private fun renderClass(k: KmClass): List<String> {
        if (k.kind == ClassKind.ENUM_CLASS) {
            return listOf("enum class ${simpleName(k)} { ${k.kmEnumEntries.joinToString(", ") { it.name }} }")
        }
        if (k.kind == ClassKind.COMPANION_OBJECT) {
            val typeParams = k.typeParameters.associate { it.id to it.name }
            return k.functions.filter { isPublicDesign(it) }
                .map { "companion ${renderFunction(it, typeParams)}" }
        }
        if (k.visibility != Visibility.PUBLIC) return emptyList() // internal: asserted elsewhere
        val typeParams = k.typeParameters.associate { it.id to it.name }
        val lines = mutableListOf(renderHeader(k, typeParams))
        k.functions.filter { isPublicDesign(it) }.forEach { lines += renderFunction(it, typeParams) }
        return lines
    }

    private fun isPublicDesign(f: KmFunction): Boolean =
        f.visibility == Visibility.PUBLIC &&
            !f.name.matches(Regex("component\\d+")) &&
            f.name !in setOf("copy", "equals", "hashCode", "toString")

    private fun renderHeader(k: KmClass, typeParams: Map<Int, String>): String {
        val kind = when {
            k.kind == ClassKind.INTERFACE -> if (k.sealedSubclasses.isNotEmpty()) "sealed interface" else "interface"
            k.kind == ClassKind.OBJECT -> if (k.isData) "data object" else "object"
            k.isData -> "data class"
            else -> "class"
        }
        val typeParamText = if (k.typeParameters.isEmpty()) "" else
            "<" + k.typeParameters.joinToString(", ") { renderTypeParam(it) } + ">"
        val publicProps = k.properties.filter { it.visibility == Visibility.PUBLIC }.map { it.name }.toSet()
        val ctor = k.constructors.firstOrNull { it.valueParameters.isNotEmpty() || publicProps.isEmpty() }
            ?.valueParameters
            ?.filter { it.name in publicProps }
            ?.joinToString(", ") { "val ${it.name}: ${renderType(it.type, typeParams)}" }
            .let { if (it.isNullOrEmpty()) "" else "($it)" }
        // `Any` is implicit; the normative list does not spell it out.
        val supers = k.supertypes
            .filterNot { (it.classifier as? KmClassifier.Class)?.name == "kotlin/Any" }
            .joinToString(", ") { renderType(it, typeParams) }
        val superClause = if (supers.isEmpty()) "" else " : $supers"
        return "$kind ${simpleName(k)}$typeParamText$ctor$superClause"
    }

    /** Metadata fqName is slash-joined; the allowlist uses simple names per the plan. */
    private fun simpleName(k: KmClass): String = k.name.substringAfterLast('/')

    private fun renderTypeParam(p: kotlin.metadata.KmTypeParameter): String = when (p.variance) {
        KmVariance.OUT -> "out ${p.name}"
        KmVariance.IN -> "in ${p.name}"
        else -> p.name
    }

    private fun renderFunction(f: KmFunction, typeParams: Map<Int, String>): String {
        val suspendPrefix = if (f.isSuspend) "suspend " else ""
        val params = f.valueParameters.joinToString(", ") { "${it.name}: ${renderType(it.type, typeParams)}" }
        return "${suspendPrefix}fun ${f.name}($params): ${renderType(f.returnType, typeParams)}"
    }

    private fun renderType(type: KmType, typeParams: Map<Int, String>): String {
        val name = when (val c = type.classifier) {
            is KmClassifier.Class -> c.name.substringAfterLast('/')
            is KmClassifier.TypeAlias -> c.name.substringAfterLast('/')
            is KmClassifier.TypeParameter -> typeParams[c.id] ?: error("type parameter ${c.id} out of scope")
        }
        val args = type.arguments.joinToString(", ") { a ->
            if (a == KmTypeProjection.STAR) "*" else when (a.variance) {
                KmVariance.IN -> "in ${renderType(a.type!!, typeParams)}"
                KmVariance.OUT -> "out ${renderType(a.type!!, typeParams)}"
                else -> renderType(a.type!!, typeParams)
            }
        }
        val base = if (args.isEmpty()) name else "$name<$args>"
        return if (type.isNullable) "$base?" else base
    }
}
