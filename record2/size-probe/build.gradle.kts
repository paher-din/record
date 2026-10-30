plugins { id("com.android.application") version "8.12.3" }

android {
    namespace = "com.paherdin.record2.backupsizeprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.paherdin.record2.backupsizeprobe"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "size-probe"
    }
    flavorDimensions += "driver"
    productFlavors {
        create("baseline") { dimension = "driver" }
        create("bundled") { dimension = "driver" }
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}

dependencies {
    // Both flavors already contain Record's Room/Kotlin baseline, so their delta does not
    // misattribute a fresh Kotlin/SQLite API runtime to the bundled native driver.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
    "bundledImplementation"("androidx.sqlite:sqlite-bundled:2.6.2")
}
