pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "record"
include(
    ":app",
    ":feature:finance",
    ":feature:notes",
    ":feature:timeline",
    ":core:model",
    ":core:data",
    ":core:ui",
    ":benchmark",
)
