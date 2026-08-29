plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.paherdin.record"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paherdin.record"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    // Tech PoC P6 (Task 1): frozen benchmark target variant. Absent from
    // main/release semantics; only this benchmark variant carries benchmark
    // source sets, the profileable declaration, and benchmark-only deps.
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(project(":feature:finance"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:timeline"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.navigation.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.test.manifest)

    // Tech PoC P6 (Task 1): benchmark-scoped only. ProfileInstaller performs the
    // Macrobenchmark profile/reset handshake on the target benchmark variant.
    // add("benchmarkImplementation", ...) is required because AGP 9 does not
    // statically generate an accessor for the custom benchmark build type.
    add("benchmarkImplementation", testFixtures(project(":core:data")))
    add("benchmarkImplementation", libs.androidx.profileinstaller)
}
