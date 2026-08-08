import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.paherdin.record.core.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

val apiBoundaryCompiler: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    api(project(":core:model"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    apiBoundaryCompiler(libs.kotlin.compiler.embeddable)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.metadata.jvm)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("apiSurface.dump", (findProperty("apiSurfaceDump") ?: "false").toString())
}

/**
 * Projection write ban (criterion 7b), part 2: independent consumer
 * compilation. Each `apiGuard` fixture compiles in its own K2JVMCompiler
 * invocation against the module's compiled classes with no friend-path
 * `internal` visibility. Positive fixtures must compile; negative fixtures
 * (one concern per file) must fail with internal-visibility or
 * unresolved-reference diagnostics, so one still-failing fixture can never
 * mask another that unexpectedly started compiling. Wired into `check`.
 */
val apiBoundaryCheck = tasks.register("apiBoundaryCheck") {
    group = "verification"
    description = "Compiles apiGuard consumer fixtures independently against :core:data's public surface"
    dependsOn("compileDebugKotlin")
    inputs.dir(layout.projectDirectory.dir("apiGuard"))
    inputs.dir(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
    outputs.dir(layout.buildDirectory.dir("apiBoundaryCheck"))

    doLast {
        val moduleClassesDir = layout.buildDirectory
            .dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").get().asFile
        check(moduleClassesDir.isDirectory) { "module classes dir missing: $moduleClassesDir" }
        val localProps = Properties().apply {
            val f = File(rootProject.projectDir, "local.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }
        val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
            ?: error("sdk.dir missing from local.properties and ANDROID_HOME unset")
        // Matches compileSdk = 36 declared above; a bump must update both.
        val androidJar = File(sdkDir, "platforms/android-36/android.jar")
        check(androidJar.isFile) { "android.jar missing: $androidJar" }
        val consumerClasspath = (listOf(moduleClassesDir, androidJar) +
            configurations.named("debugCompileClasspath").get().files)
            .joinToString(File.pathSeparator) { it.absolutePath }
        val compilerClasspath = configurations.named("apiBoundaryCompiler").get().files
            .joinToString(File.pathSeparator) { it.absolutePath }
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        val javaExe = File(System.getProperty("java.home"), "bin/java${if (isWindows) ".exe" else ""}")
        check(javaExe.isFile) { "java executable missing: $javaExe" }

        fun compileFixture(fixture: File, outDir: File): Pair<Int, String> {
            val command = listOf(
                javaExe.absolutePath, "-cp", compilerClasspath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-classpath", consumerClasspath,
                "-d", outDir.absolutePath,
                "-nowarn",
                fixture.absolutePath,
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        }

        val guardDir = layout.projectDirectory.dir("apiGuard").asFile
        val outRoot = layout.buildDirectory.dir("apiBoundaryCheck").get().asFile
        var failures = 0

        File(guardDir, "positive").listFiles { f -> f.extension == "kt" }!!.forEach { fixture ->
            val (exit, output) = compileFixture(fixture, File(outRoot, "positive"))
            if (exit != 0) {
                failures++
                println("POSITIVE fixture must compile: ${fixture.name}")
                println(output)
            }
        }
        File(guardDir, "negative").listFiles { f -> f.extension == "kt" }!!.forEach { fixture ->
            val (exit, output) = compileFixture(fixture, File(outRoot, "negative"))
            val expectedDiagnostic =
                output.contains("internal", ignoreCase = true) || output.contains("unresolved reference", ignoreCase = true)
            if (exit == 0 || !expectedDiagnostic) {
                failures++
                println("NEGATIVE fixture must fail with internal/unresolved diagnostics: ${fixture.name} (exit=$exit)")
                println(output)
            }
        }

        check(failures == 0) { "apiBoundaryCheck: $failures fixture(s) violated the boundary; see output above" }
        println("apiBoundaryCheck: PASS (${File(guardDir, "positive").listFiles().size} positive, " +
            "${File(guardDir, "negative").listFiles().size} negative fixtures)")
    }
}

tasks.named("check") {
    dependsOn(apiBoundaryCheck)
}
