import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}

// ADR 0011: the production graph is exactly these seven modules. A test-tool
// project is a separate, allowlisted Gradle project (currently only
// `:benchmark`) and is not part of the production graph.
val productionModules = setOf(
    ":app", ":feature:finance", ":feature:notes", ":feature:timeline",
    ":core:model", ":core:data", ":core:ui",
)

val productionEdges = mapOf(
    ":app" to setOf(":feature:finance", ":feature:notes", ":feature:timeline", ":core:data", ":core:ui"),
    ":feature:finance" to setOf(":core:model", ":core:data", ":core:ui"),
    ":feature:notes" to setOf(":core:model", ":core:data", ":core:ui"),
    ":feature:timeline" to setOf(":core:model", ":core:data", ":core:ui"),
    ":core:data" to setOf(":core:model"),
    ":core:ui" to setOf(":core:model"),
    ":core:model" to emptySet(),
)

// ADR 0011 test-tool allowlist. `:benchmark`'s only project relation is the
// com.android.test target edge to `:app`; it must never appear as a
// ProjectDependency edge to/from any module.
val testToolAllowlist = setOf(":benchmark")
val benchmarkTargetProject = ":app"

// Developer-declared configurations whose project edges are governed. AGP 9
// built-in Kotlin injects self ProjectDependencies into generated test
// compile/runtime configurations; only these declared configurations (plus the
// benchmark-variant family) are checked. Add new declared configurations here.
val declaredModuleConfigurations = setOf(
    "implementation", "api", "compileOnly", "runtimeOnly",
    "testImplementation", "androidTestImplementation",
    "debugImplementation", "releaseImplementation",
    "benchmarkImplementation", "benchmarkApi",
)

data class ModuleGraphFacts(
    val productionSet: Set<String>,
    val testToolSet: Set<String>,
    val edges: Map<String, Set<String>>,
    val testToolTargets: Map<String, String?>,
)

/** Pure set/edge validation. Returns every violation; an empty list is PASS. */
fun validateModuleGraphFacts(
    facts: ModuleGraphFacts,
    expectedProduction: Set<String>,
    expectedTestTools: Set<String>,
    expectedEdges: Map<String, Set<String>>,
    expectedBenchmarkTarget: String,
): List<String> {
    val violations = mutableListOf<String>()
    if (facts.productionSet != expectedProduction) {
        violations += "Production module set mismatch: expected $expectedProduction, found ${facts.productionSet}"
    }
    if (facts.testToolSet != expectedTestTools) {
        violations += "Test-tool project set mismatch: expected $expectedTestTools, found ${facts.testToolSet}"
    }
    expectedProduction.forEach { mod ->
        val allowed = expectedEdges[mod] ?: emptySet()
        val bad = (facts.edges[mod] ?: emptySet()) - allowed
        if (bad.isNotEmpty()) violations += "Illegal production dependency edges from $mod: $bad"
        val toTestTools = (facts.edges[mod] ?: emptySet()).intersect(expectedTestTools)
        if (toTestTools.isNotEmpty()) violations += "Production module $mod depends on test-tool project(s): $toTestTools"
    }
    facts.testToolSet.forEach { tt ->
        val deps = facts.edges[tt] ?: emptySet()
        if (deps.isNotEmpty()) violations += "Test-tool project $tt declares project dependency edge(s): $deps"
        if (facts.testToolTargets[tt] != expectedBenchmarkTarget) {
            violations += "Test-tool project $tt targetProjectPath expected '$expectedBenchmarkTarget', " +
                "found '${facts.testToolTargets[tt]}'"
        }
    }
    return violations
}

/** ADR 0012 native settings reads are allowed; writes/deletes are not. */
fun testToolTargetPathOf(project: Project): String? {
    val android = project.extensions.findByName("android") ?: return null
    return runCatching {
        val getter = android.javaClass.methods.firstOrNull {
            it.name == "getTargetProjectPath" && it.parameterCount == 0
        }
        getter?.invoke(android) as? String
    }.getOrNull()
}

/**
 * Refresh-write source assertion (spec criterion 7b / criterion 3): the harness
 * must never `settings put|delete system peak_refresh_rate|min_refresh_rate`
 * anywhere under the test-tool source (benchmark/src/main) or the target app's
 * benchmark source set (app/src/benchmark). Reads (`settings get`) and the
 * navigation-mode writes (`settings put|delete global ...`) remain allowed.
 */
fun refreshSettingWriteViolations(rootDir: File): List<Pair<File, String>> {
    val pattern = Regex("""(?i)\bsettings\s+(put|delete)\s+system\s+(peak_refresh_rate|min_refresh_rate)\b""")
    val roots = listOf(
        File(rootDir, "benchmark/src/main"),
        File(rootDir, "app/src/benchmark"),
    )
    return roots.flatMap { dir ->
        if (!dir.isDirectory) return@flatMap emptyList()
        dir.walkTopDown().filter { it.isFile }.flatMap { f ->
            pattern.findAll(f.readText()).map { f to it.value }
        }.toList()
    }
}

tasks.register("checkModuleDependencies") {
    group = "verification"
    description = "Enforces the exact production module set/edges and the ADR 0011 test-tool allowlist"
    doLast {
        // A module is a project with a build script. Gradle 9 implicitly creates
        // container projects (":core", ":feature") for nested includes; they are
        // not modules and cannot declare dependencies.
        val modules = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }
        val productionSet = modules.filterNot { it.plugins.hasPlugin("com.android.test") }.map { it.path }.toSet()
        val testToolSet = modules.filter { it.plugins.hasPlugin("com.android.test") }.map { it.path }.toSet()
        val edges = modules.associate { project ->
            val deps = project.configurations
                .filter { it.name in declaredModuleConfigurations }
                .flatMap { it.dependencies }
                .filterIsInstance<ProjectDependency>()
                .map { it.path }
                .toSet()
            project.path to deps
        }
        val testToolTargets = testToolSet.associateWith { path ->
            testToolTargetPathOf(modules.first { it.path == path })
        }
        val violations = validateModuleGraphFacts(
            facts = ModuleGraphFacts(productionSet, testToolSet, edges, testToolTargets),
            expectedProduction = productionModules,
            expectedTestTools = testToolAllowlist,
            expectedEdges = productionEdges,
            expectedBenchmarkTarget = benchmarkTargetProject,
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module boundary violations:\n" + violations.joinToString("\n") { "  - $it" },
            )
        }
        val refreshWrites = refreshSettingWriteViolations(rootProject.projectDir)
        if (refreshWrites.isNotEmpty()) {
            throw GradleException(
                "Forbidden refresh-setting write(s) found: " +
                    refreshWrites.joinToString(", ") { "${it.first}: ${it.second}" },
            )
        }
        println(
            "checkModuleDependencies: PASS (${
                productionModules.size
            } production modules, test-tool = $testToolAllowlist, refresh-write scan clean)",
        )
    }
}

gradle.projectsEvaluated {
    tasks.register("check") {
        dependsOn("checkModuleDependencies")
    }
}
