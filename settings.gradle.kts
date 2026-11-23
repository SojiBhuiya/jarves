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
        // 0.2.20: NewPipe Extractor (YouTube stream extraction) ships through JitPack,
        // not Maven Central. Only TeamNewPipe artifacts may come from here.
        maven("https://jitpack.io") {
            mavenContent { includeGroup("com.github.TeamNewPipe") }
        }
    }
}
rootProject.name = "Jarves"
include(":app")
