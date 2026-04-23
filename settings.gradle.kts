enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    buildscript {
        repositories {
            mavenCentral()
            maven(url = "https://storage.googleapis.com/r8-releases/raw")
        }
        dependencies {
            classpath("com.android.tools:r8:8.13.19")
        }
    }
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
    }
    versionCatalogs {
        create("libs") {
            from(files("core/gradle/libs.versions.toml"))
        }
        create("npatch") {
            from(files("gradle/npatch.versions.toml"))
        }
    }
}

rootProject.name = "NPatch"
include(
    ":apkzlib",
    ":jar",
    ":manager",
    ":meta-loader",
    ":patch",
    ":patch-loader",
    ":share:android",
    ":share:java",
)

includeBuild("core") {
    dependencySubstitution {
        substitute(module("vector:axml")).using(project(":external:axml"))
        substitute(module("vector:bridge")).using(project(":hiddenapi:bridge"))
        substitute(module("vector:legacy")).using(project(":legacy"))
        substitute(module("vector:core")).using(project(":xposed"))
        substitute(module("vector:daemon-service")).using(project(":services:daemon-service"))
        substitute(module("vector:stubs")).using(project(":hiddenapi:stubs"))
    }
}