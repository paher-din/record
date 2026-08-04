import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

val allowedModules = setOf(
    ":app", ":feature:finance", ":feature:notes", ":feature:timeline",
    ":core:model", ":core:data", ":core:ui",
)

val allowedEdges = mapOf(
    ":app" to setOf(":feature:finance", ":feature:notes", ":feature:timeline", ":core:data", ":core:ui"),
    ":feature:finance" to setOf(":core:model", ":core:data", ":core:ui"),
    ":feature:notes" to setOf(":core:model", ":core:data", ":core:ui"),
    ":feature:timeline" to setOf(":core:model", ":core:data", ":core:ui"),
    ":core:data" to setOf(":core:model"),
    ":core:ui" to setOf(":core:model"),
    ":core:model" to emptySet(),
)

tasks.register("checkModuleDependencies") {
    doLast {
        // A module is a project with a build script. Gradle 9 implicitly creates
        // container projects (":core", ":feature") for nested includes; they are
        // not modules and cannot declare dependencies.
        val modules = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }
        val projects = modules.map { it.path }.toSet()
        check(projects == allowedModules) {
            "Module set mismatch: expected $allowedModules, found $projects"
        }
        // AGP 9 built-in Kotlin injects self ProjectDependencies into generated
        // test compile/runtime configurations; only developer-declared
        // configurations are checked. Add new declared configurations here.
        val declaredConfigurations = setOf(
            "implementation", "api", "compileOnly", "runtimeOnly",
            "testImplementation", "androidTestImplementation",
            "debugImplementation", "releaseImplementation",
        )
        modules.forEach { project ->
            val deps = project.configurations
                .filter { it.name in declaredConfigurations }
                .flatMap { it.dependencies }
                .filterIsInstance<ProjectDependency>()
                .map { it.path }
                .toSet()
            val violations = deps - (allowedEdges[project.path] ?: emptySet())
            check(violations.isEmpty()) {
                "Illegal project dependency edges from ${project.path}: $violations"
            }
        }
        println("checkModuleDependencies: PASS (7 modules, graph conforms)")
    }
}

gradle.projectsEvaluated {
    tasks.register("check") {
        dependsOn("checkModuleDependencies")
    }
}
