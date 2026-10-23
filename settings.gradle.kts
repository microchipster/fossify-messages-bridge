pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}
include(":app")

// Local mmslib fork replaces the org.fossify:mmslib JitPack artifact.
// Override the checkout location with the MMSLIB_DIR environment variable.
include(":mmslib")
project(":mmslib").projectDir = file(System.getenv("MMSLIB_DIR") ?: "../fossify-mmslib/library")
