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
    // Tech PoC P6 (Task 1): benchmark build type for the shadow projection
    // source set (Task 3+). Release-identical; consumed only via :app's
    // benchmarkImplementation testFixtures edge.
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
        }
    }
    // Tech PoC P4: shared JVM fixtures (corpus generator, normalizer, oracle,
    // selection rule) consumed by both unit tests and instrumented tests.
    testFixtures {
        enable = true
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

    // M1-T09: the device-side fixture `prepare` writes generation databases
    // through the production Room DAOs (openGenerationDatabase/recordDao) and
    // awaits the production StagedGenerationValidator, so the testFixtures
    // compile classpath must carry room-runtime (which also provides the
    // coroutines entry points it uses).
    testFixturesImplementation(libs.room.runtime)

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

/**
 * Tech PoC P6 (Task 2): host JavaExec over the unit-test runtime that
 * regenerates the frozen `timeline-v1` fixture and oracle artifacts under
 * `core/data/build/p6/` — `fixture.sha256`, `oracle.sha256`, `oracle.txt`.
 * Outputs are build artifacts, never committed. The classpath is taken from
 * the unit-test `Test` task (AGP wires the module's own testFixtures and
 * output directly there; a standalone resolution of
 * `debugUnitTestRuntimeClasspath` is ambiguous between the module's own debug
 * runtime and testFixtures variants).
 */
val emitP6Oracle = tasks.register<JavaExec>("emitP6Oracle") {
    group = "verification"
    description = "Regenerates core/data/build/p6 fixture and oracle artifacts from the frozen timeline-v1 fixture"
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn("compileDebugUnitTestKotlin", "compileDebugTestFixturesKotlin")
    classpath = unitTest.classpath
    mainClass.set("com.paherdin.record.core.data.timeline.TimelineArtifactEmitterKt")
    systemProperty("p6OutputDir", layout.buildDirectory.dir("p6").get().asFile.absolutePath)
    outputs.dir(layout.buildDirectory.dir("p6"))
}

/**
 * Tech PoC P6 (Task 6): host JavaExec over the unit-test runtime that runs the
 * strict Revision 2/3 validator (`TimelineBenchmarkReportValidator`) against
 * the copied device evidence. Invocation (plan Task 6 Step 8):
 *
 * ```
 * .\gradlew.bat "-Dp6ReportDir=<copied additional output root>" :core:data:validateP6BenchmarkReports
 * ```
 *
 * The `-D` value arrives on the Gradle daemon JVM and is forwarded to the
 * forked JVM here; an absent value fails with a precise error from the
 * validator entry point. The task validates only copied host evidence: exact
 * four blocks, 30 positive unique query samples, 5 valid full-scroll frame
 * arrays with five positive sub-cap gesture counts (Revision 3: equality not
 * required; min/max/range reported), 35 RssAnon maxima, 35 distinct
 * before/after refresh pairs, exact native settings before/after, 35 Perfetto
 * traces, and the four gates recomputed from raw samples. Pure read: it never
 * touches the device.
 */
val validateP6BenchmarkReports = tasks.register<JavaExec>("validateP6BenchmarkReports") {
    group = "verification"
    description = "Validates copied Task 6 benchmark reports against the frozen Revision 2/3 contract and budgets"
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn("compileDebugUnitTestKotlin")
    classpath = unitTest.classpath
    mainClass.set("com.paherdin.record.core.data.timeline.TimelineBenchmarkReportValidatorTestKt")
    System.getProperty("p6ReportDir")?.let { systemProperty("p6ReportDir", it) }
}

tasks.named("check") {
    dependsOn(apiBoundaryCheck)
}

/**
 * M1-T09 (Task 6): host JavaExec over the unit-test runtime that runs the
 * strict M1 startup report validator (`M1StartupReportValidator`) against the
 * copied device evidence. Invocation (the T10 device-task gate):
 *
 * ```
 * .\gradlew.bat "-Dm1ReportDir=<copied benchmark report root>" :core:data:validateM1StartupReports
 * ```
 *
 * The `-D` value arrives on the Gradle daemon JVM and is forwarded to the
 * forked JVM here; an absent value fails with a precise error from the
 * validator entry point. The task validates only copied host evidence: exact
 * eight blocks, 30 positive finite samples per binding (TTFD present exactly
 * on the ttfdRequired blocks, absent on the pending-migration blocks),
 * nearest-rank rank-29 TTID/TTFD budgets, 240 Perfetto traces, and the exact
 * frozen run manifest (identity, environment, command log, 240 ordered
 * iteration records). Pure read: it never touches the device.
 */
val validateM1StartupReports = tasks.register<JavaExec>("validateM1StartupReports") {
    group = "verification"
    description = "Validates copied M1-T09/T10 startup benchmark reports against the frozen m1-startup-v1 contract and budgets"
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn("compileDebugUnitTestKotlin")
    classpath = unitTest.classpath
    mainClass.set("com.paherdin.record.core.data.startup.M1StartupReportValidatorKt")
    System.getProperty("m1ReportDir")?.let { systemProperty("m1ReportDir", it) }
}
