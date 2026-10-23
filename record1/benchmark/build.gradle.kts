// Tech PoC P6 (Task 1): ADR 0011 test-tool project. Out-of-process
// Macrobenchmark harness; no project dependency on any production module —
// the only relation is the com.android.test target edge to :app.
// Versionless id: com.android.test shares the AGP jar with application/library,
// so it is registered on the root classpath and applied here without re-resolving.
plugins {
    id("com.android.test")
}

android {
    namespace = "com.paherdin.record.benchmark.test"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The test module's own benchmark build type selects :app's benchmark
    // target variant and makes connectedBenchmarkAndroidTest/assembleBenchmark
    // concrete tasks. Initialized from debug (com.android.test has no release
    // build type) so the instrumentation test APK stays debuggable.
    buildTypes {
        create("benchmark") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"

    // Self-instrumenting mode (benchmark-macro 1.4.1 requirement, plan Task 1
    // "self-instrumenting mode required by the pinned plugin" — this flag was
    // discovered missing at Task 6 Step 7 when MacrobenchmarkRule's
    // ConfigurationError check aborted the first binding run). Without it the
    // instrumentation is loaded into the target application process, which
    // prevents Macrobenchmark from killing/compiling/launching the target.
    // Consequence (Task 6 repair): the instrumentation's targetContext is the
    // test package itself, so all benchmark-module tests read the target app's
    // sentinels through TimelineSentinelBridge (app-published
    // p6_sentinels.json in the app's external media dir, read via shell cat).
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
    // Version-alignment fix (Task 3 deviation): the AndroidJUnitRunner's
    // MonitoringInstrumentation (androidx.test:monitor 1.7.2) references
    // androidx.lifecycle.ReportFragment.Companion, which needs lifecycle-runtime
    // >= 2.8, but benchmark-macro 1.4.1 pulls 2.3.1. Align to the M1-selected
    // Lifecycle 2.10.0 catalog version so the instrumented app process never
    // loads the old ReportFragment.
    implementation(libs.lifecycle.runtime)
    // Task 4 alignment (same class of fix): benchmark-macro:1.4.1 resolves
    // androidx.core:core:1.9.0 / androidx.collection:collection:1.0.0 into the
    // test APK, which shadow the target app's newer androidx classes in the
    // shared instrumented process and crash ComponentActivity init
    // (SimpleArrayMap(II,DefaultConstructorMarker) NoSuchMethodError). Align to
    // the versions the :app benchmark variant already resolves via the Compose
    // BOM (core 1.18.0, collection 1.5.0).
    implementation("androidx.collection:collection:1.5.0")
    implementation("androidx.core:core:1.18.0")
}
