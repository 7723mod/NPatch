enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    buildscript {
        repositories {
            mavenCentral()
            maven(url = "https://storage.googleapis.com/r8-releases/raw")
        }
        dependencies {
            classpath("com.android.tools:r8:8.13.6")
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
        mavenLocal {
            content {
                includeGroup("io.github.libxposed")
            }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("core/gradle/libs.versions.toml"))
        }
        create("npatch") {
            from(files("gradle/lspatch.versions.toml"))
        }
    }
}

rootProject.name = "NPatch"
include(
    ":apkzlib",
    ":core",
    ":external:apache",
    ":external:axml",
    ":hiddenapi:bridge",
    ":hiddenapi:stubs",
    ":jar",
    ":manager",
    ":meta-loader",
    ":patch",
    ":patch-loader",
    ":services:daemon-service",
    ":services:manager-service",
    ":services:xposed-service:interface",
    ":share:android",
    ":share:java",
    ":xposed"
)

project(":core").projectDir = file("core/core")
project(":external:apache").projectDir = file("core/external/apache")
project(":external:axml").projectDir = file("core/external/axml")
project(":hiddenapi:bridge").projectDir = file("core/hiddenapi/bridge")
project(":hiddenapi:stubs").projectDir = file("core/hiddenapi/stubs")
project(":services:daemon-service").projectDir = file("core/services/daemon-service")
project(":services:manager-service").projectDir = file("core/services/manager-service")
project(":services:xposed-service:interface").projectDir = file("core/services/xposed-service/interface")
project(":xposed").projectDir = file("core/xposed")
