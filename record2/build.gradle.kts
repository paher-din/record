import com.android.build.gradle.AppExtension
import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

fun boundaryViolations(path: String, source: String): List<String> = buildList {
    val area = path.substringBefore('/')
    if (area != "ai" && Regex("\\bai\\.koog\\.").containsMatchIn(source)) {
        add("Koog belongs to the ai adapter")
    }
    if (area in setOf("ui", "ai", "records", "proposals", "sessions", "backup") &&
        Regex("\\b(androidx\\.room|com\\.paherdin\\.record2\\.storage)\\.").containsMatchIn(source)) {
        add("Call the application interface instead of persistence")
    }
    if (path.startsWith("ai/tools/") &&
        Regex("\\b(CommitService|commitChanges)\\b|\\.proposals\\.commit\\.").containsMatchIn(source)) {
        add("Tools cannot receive the native commit capability")
    }
}

val verificationClasspaths = listOf("debugCompileClasspath", "debugRuntimeClasspath", "releaseCompileClasspath",
    "releaseRuntimeClasspath", "debugUnitTestCompileClasspath", "debugUnitTestRuntimeClasspath",
    "debugAndroidTestCompileClasspath", "debugAndroidTestRuntimeClasspath")

fun Configuration.androidClasses() = incoming.artifactView {
    attributes.attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar")
}

val verifyBuildIsolation = tasks.register("verifyBuildIsolation") {
    group = "verification"
    description = "Checks R2 build/source boundaries and records actual compile/runtime inputs"
    doLast {
        val buildRoot = rootDir.canonicalFile.toPath()
        fun requireOwn(file: File) {
            check(file.canonicalFile.toPath().startsWith(buildRoot)) { "Input/output outside the R2 root: $file" }
        }
        check(rootProject.name == "record2")
        check(rootProject.allprojects.map { it.path }.toSet() == setOf(":", ":app"))
        check(gradle.includedBuilds.isEmpty()) { "R2 cannot include another build" }
        check(gradle.startParameter.initScripts.isEmpty()) { "Review injected init scripts before building R2" }
        allprojects.forEach {
            requireOwn(it.projectDir)
            requireOwn(it.buildFile)
            requireOwn(it.layout.buildDirectory.get().asFile)
        }
        gradle.taskGraph.allTasks.forEach { requireOwn(it.project.projectDir) }

        check(boundaryViolations("ui/Screen.kt", "import androidx.room.Room as HiddenRoom").isNotEmpty())
        check(boundaryViolations("ui/Screen.kt", "com.paherdin.record2.storage.RecordDao()").isNotEmpty())
        check(boundaryViolations("ai/tools/Tools.kt", "import x.CommitService as C").isNotEmpty())
        check(boundaryViolations("records/Service.kt", "import ai.koog.agents.core.agent.AIAgent").isNotEmpty())
        check(boundaryViolations("ai/Adapter.kt", "import ai.koog.agents.core.agent.AIAgent").isEmpty())
        check(boundaryViolations("records/Money.kt", "package com.paherdin.record2.records").isEmpty())

        val app = project(":app")
        val android = app.extensions.getByType<AppExtension>()
        val sourceInputs = android.sourceSets.flatMap { source ->
            source.java.srcDirs + source.res.srcDirs + source.assets.srcDirs +
                source.resources.srcDirs + source.aidl.srcDirs + source.jni.srcDirs +
                source.jniLibs.srcDirs + source.shaders.srcDirs + source.mlModels.srcDirs +
                source.renderscript.srcDirs + source.baselineProfiles.directories.map { app.file(it) } +
                source.manifest.srcFile
        } + app.extensions.getByType<KotlinAndroidProjectExtension>().sourceSets.flatMap { it.kotlin.srcDirs }
        sourceInputs.forEach(::requireOwn)
        val productionSets = setOf("main") + android.buildTypes.names
        val codeRoots = android.sourceSets.filter { it.name in productionSets }.flatMap { it.java.srcDirs } +
            app.extensions.getByType<KotlinAndroidProjectExtension>().sourceSets
                .filter { it.name in productionSets }.flatMap { it.kotlin.srcDirs }
        codeRoots.distinct().forEach { codeRoot ->
            codeRoot.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.forEach { file ->
                val path = file.invariantSeparatorsPath.substringAfter("com/paherdin/record2/")
                val errors = boundaryViolations(path, file.readText())
                check(errors.isEmpty()) { "${file.name}: ${errors.joinToString()}" }
            }
        }

        val legacy = rootDir.parentFile.resolve("record1").canonicalFile.toPath()
        val dependencies = verificationClasspaths.associateWith { name ->
            app.configurations.getByName(name).androidClasses().artifacts.artifacts.map { artifact ->
                check(!artifact.file.canonicalFile.toPath().startsWith(legacy)) { "R1 artifact in $name" }
                val component = artifact.id.componentIdentifier
                if (component is ProjectComponentIdentifier) {
                    check(component.projectPath == ":app" && component.build.buildPath == ":") {
                        "Another project/build entered $name: $component"
                    }
                }
                mapOf("component" to component.displayName, "file" to artifact.file.canonicalPath)
            }.sortedBy { it["component"] }
        }
        val report = mapOf("root" to rootDir.canonicalPath, "projects" to allprojects.map { it.path },
            "gradle" to gradle.gradleVersion, "java" to System.getProperty("java.version"),
            "includedBuilds" to emptyList<String>(), "explicitInitScripts" to emptyList<String>(),
            "sourceInputs" to sourceInputs.map { it.canonicalPath }.distinct().sorted(),
            "taskGraph" to gradle.taskGraph.allTasks.map { it.path }, "dependencies" to dependencies)
        val output = layout.buildDirectory.file("a1/build-isolation.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)))
        println("verifyBuildIsolation: PASS (one app, own inputs/outputs, boundary controls, eight classpaths)")
    }
}

gradle.projectsEvaluated {
    val app = project(":app")
    verifyBuildIsolation.configure {
        inputs.files(verificationClasspaths.map { app.configurations.getByName(it).androidClasses().files })
        inputs.files(app.fileTree("src"))
    }
}

project(":app") {
    plugins.withId("com.android.application") {
        tasks.named("check") { dependsOn(verifyBuildIsolation) }
    }
}

tasks.register("check") {
    group = "verification"
    dependsOn(":app:check")
}
