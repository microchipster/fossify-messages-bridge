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
    }
}
include(":app")

// The mmslib fork is vendored as a git submodule (see mmslib/).
// Override the checkout location with the MMSLIB_DIR environment variable.
include(":mmslib")
project(":mmslib").projectDir = file(System.getenv("MMSLIB_DIR") ?: "mmslib/library")
