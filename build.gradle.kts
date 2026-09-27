import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.BaseExtension
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.kotlin.dsl.extra

plugins {
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    alias(npatch.plugins.compose.compiler) apply false
    alias(npatch.plugins.kotlin.android) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    }
}

val commitCount = runCatching {
    val repo = FileRepository(rootProject.file(".git"))
    val refId = repo.refDatabase.exactRef("refs/remotes/origin/miuix")?.objectId
    if (refId != null) Git(repo).log().add(refId).call().count() else 0
}.getOrElse {0}

val coreCommitCount = runCatching {
    FileRepositoryBuilder().setGitDir(rootProject.file("core/.git"))
        .setWorkTree(rootProject.file("core"))
        .build().use { repo ->
            val git = Git(repo)
            git.log().add(repo.resolve("HEAD")).call().count()
        }
}.getOrDefault(3083)

val defaultManagerPackageName by extra("top.nkbe.npatch")
val apiCode by extra(102)
val verCode by extra(commitCount)
val verName by extra("1.0.7")
val coreVerCode by extra(coreCommitCount)
val coreVerName by extra("v2.2-core")
val androidMinSdkVersion by extra(28)
val androidTargetSdkVersion by extra(37)
val androidCompileSdkVersion by extra(37)
val androidCompileNdkVersion by extra("29.0.13846066")
val androidBuildToolsVersion by extra("37.0.0")
val androidSourceCompatibility by extra(JavaVersion.VERSION_21)
val androidTargetCompatibility by extra(JavaVersion.VERSION_21)

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

listOf("Debug", "Release").forEach { variant ->
    val variantLower = variant.lowercase()
    val remoteApiTask = tasks.register<Copy>("buildRemoteApi$variant") {
        description = "Build and collect the NPatch Remote API $variant AAR"
        dependsOn(":remote-api:assemble$variant")
        from(project(":remote-api").layout.buildDirectory.dir("outputs/aar")) {
            include("remote-api-$variantLower.aar")
            rename { "npatch-remote-api-v1.0.0-$variantLower.aar" }
        }
        into(layout.projectDirectory.dir("out/$variantLower"))
    }

    tasks.register("build$variant") {
        description = "Build NPatch with $variant"
        dependsOn(tasks.findByPath(":jar:build$variant") ?: "jar:build$variant")
        dependsOn(tasks.findByPath(":manager:build$variant") ?: "manager:build$variant")
        dependsOn(remoteApiTask)
    }
}

tasks.register("buildAll") {
    dependsOn("buildDebug", "buildRelease")
}

fun Project.configureBaseExtension() {
    extensions.findByType(BaseExtension::class)?.run {
        compileSdkVersion(androidCompileSdkVersion)
        ndkVersion = androidCompileNdkVersion
        buildToolsVersion = androidBuildToolsVersion

        externalNativeBuild.cmake {
            version = "3.29.8+"
            buildStagingDirectory = layout.buildDirectory.get().asFile
        }

        defaultConfig {
            minSdk = androidMinSdkVersion
            targetSdk = androidTargetSdkVersion

            externalNativeBuild {
                cmake {
                    arguments += "-DVECTOR_ROOT=${File(rootDir.absolutePath, "core")}"
                    arguments += "-DEXTERNAL_ROOT=${File(rootDir.absolutePath, "core/external")}"
                    arguments += "-DCORE_ROOT=${File(rootDir.absolutePath, "core/native") }"
                    abiFilters("arm64-v8a", "x86_64")
                    val flags = arrayOf(
                        "-Wall",
                        "-Qunused-arguments",
                        "-Wno-gnu-string-literal-operator-template",
                        "-fno-rtti",
                        "-fvisibility=hidden",
                        "-fvisibility-inlines-hidden",
                        "-fno-exceptions",
                        "-fno-stack-protector",
                        "-fomit-frame-pointer",
                        "-Wno-builtin-macro-redefined",
                        "-Wno-unused-value",
                        "-D__FILE__=__FILE_NAME__",
                    )
                    cppFlags("-std=c++20", *flags)
                    cFlags("-std=c18", *flags)
                    arguments(
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                        "-DVERSION_CODE=$verCode",
                        "-DVERSION_NAME=$verName",
                    )
                }
            }
        }

        compileOptions {
            targetCompatibility(androidTargetCompatibility)
            sourceCompatibility(androidSourceCompatibility)
        }

        buildTypes {
            named("debug") {
                externalNativeBuild {
                    cmake {
                        arguments.addAll(
                            arrayOf(
                                "-DCMAKE_CXX_FLAGS_DEBUG=-Og",
                                "-DCMAKE_C_FLAGS_DEBUG=-Og",
                            )
                        )
                    }
                }
            }
            named("release") {
                externalNativeBuild {
                    cmake {
                        val flags = arrayOf(
                            "-Wl,--exclude-libs,ALL",
                            "-ffunction-sections",
                            "-fdata-sections",
                            "-Wl,--gc-sections",
                            "-fno-unwind-tables",
                            "-fno-asynchronous-unwind-tables",
                            "-flto=thin",
                            "-Wl,--thinlto-cache-policy,cache_size_bytes=300m",
                            "-Wl,--thinlto-cache-dir=${layout.buildDirectory.get().asFile.absolutePath}/.lto-cache", 
                        )
                        cppFlags.addAll(flags)
                        cFlags.addAll(flags)
                        val configFlags = arrayOf(
                            "-Oz",
                            "-DNDEBUG"
                        ).joinToString(" ")
                        arguments.addAll(
                            arrayOf(
                                "-DCMAKE_CXX_FLAGS_RELEASE=$configFlags",
                                "-DCMAKE_CXX_FLAGS_RELWITHDEBINFO=$configFlags",
                                "-DCMAKE_C_FLAGS_RELEASE=$configFlags",
                                "-DCMAKE_C_FLAGS_RELWITHDEBINFO=$configFlags",
                                "-DDEBUG_SYMBOLS_PATH=${layout.buildDirectory.get().asFile.absolutePath}/symbols", 
                            )
                        )
                    }
                }
            }
        }
    }
}

fun Project.configureApplicationExtension(extension: ApplicationExtension) {
    extension.run {
        defaultConfig {
            versionCode = verCode
            versionName = verName
        }

        val config = signingConfigs.create("config") {
            val androidStoreFile = (
                System.getenv("ANDROID_STORE_FILE")
                    ?: project.findProperty("androidStoreFile")?.toString()
                )?.takeIf { it.isNotBlank() }
            val androidStorePassword = System.getenv("ANDROID_STORE_PASSWORD")
                ?: project.findProperty("androidStorePassword")?.toString()
            val androidKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
                ?: project.findProperty("androidKeyAlias")?.toString()
            val androidKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                ?: project.findProperty("androidKeyPassword")?.toString()

            if (androidStoreFile != null && androidStorePassword != null && androidKeyAlias != null && androidKeyPassword != null) {
                storeFile = rootProject.file(androidStoreFile)
                storePassword = androidStorePassword
                keyAlias = androidKeyAlias
                keyPassword = androidKeyPassword
            }
            enableV2Signing = true
            enableV3Signing = true
        }
        val selectedSigningConfig = if (config.storeFile != null) config else signingConfigs["debug"]
        buildTypes.configureEach {
            signingConfig = selectedSigningConfig
        }
        lint {
            abortOnError = true
            checkReleaseBuilds = false
        }
    }

    extensions.findByType(ApplicationAndroidComponentsExtension::class)?.let { androidComponents ->
        val optimizeReleaseRes = tasks.register("optimizeReleaseRes") {
            doLast {
                val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                val aapt2Name = if (isWindows) "aapt2.exe" else "aapt2"

                val aapt2 = File(
                    androidComponents.sdkComponents.sdkDirectory.get().asFile,
                    "build-tools/${androidBuildToolsVersion}/$aapt2Name"
                )
                val zip = project.layout.buildDirectory.get().asFile.toPath()
                    .resolve("intermediates")
                    .resolve("optimized_processed_res")
                    .resolve("release")
                    .resolve("optimizeReleaseResources")
                    .resolve("resources-release-optimize.ap_")
                val optimized = File("${zip}.opt")
                val cmd = providers.exec {
                    commandLine(
                        aapt2, "optimize",
                        "--collapse-resource-names",
                        "--enable-sparse-encoding",
                        "-o", optimized,
                        zip
                    )
                    isIgnoreExitValue = false
                }.result.get()
                if (cmd.exitValue == 0) {
                    delete(zip)
                    optimized.renameTo(zip.toFile())
                }
            }
        }

        tasks.configureEach {
            if (name == "optimizeReleaseResources") {
                finalizedBy(optimizeReleaseRes)
            }
        }
    }
}

subprojects {
    plugins.withId("com.android.application") {
        configureBaseExtension()
        extensions.findByType(ApplicationExtension::class)?.let {
            configureApplicationExtension(it)
        }
    }
    plugins.withId("com.android.library") {
        configureBaseExtension()
    }
}
