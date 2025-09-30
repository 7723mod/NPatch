import java.io.File
import java.util.Locale
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.agp.app)
}

android {
    defaultConfig {
        multiDexEnabled = false
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }
    namespace = "org.lsposed.lspatch.loader"
}

val assembleReleaseTaskProvider = tasks.named("assembleRelease")

androidComponents.onVariants { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase(Locale.ROOT) }
    val projectDir = rootProject.layout.projectDirectory

    val assembleVariantTask = tasks.named("assemble$variantCapped")

    val copyDexTask = tasks.register<Copy>("copyDex$variantCapped") {
        dependsOn(assembleVariantTask)

        from(
            layout.buildDirectory.file("intermediates/dex/${variant.name}/mergeDex$variantCapped/classes.dex")
        )
        rename("classes.dex", "loader.dex")

        into(projectDir.dir("out/assets/${variant.name}/lspatch"))
    }

    val copySoTask = tasks.register<Copy>("copySo$variantCapped") {
        dependsOn(assembleVariantTask)
        dependsOn("strip${variantCapped}DebugSymbols")

        val strippedLibsDir = layout.buildDirectory.dir("intermediates/stripped_native_libs/${variant.name}/strip${variantCapped}DebugSymbols/out/lib")

        from(
            fileTree(
                strippedLibsDir.get().asFile
            ) {
                include(listOf("**/liblspatch.so"))
            }
        )
        into(projectDir.dir("out/assets/${variant.name}/lspatch/so"))
    }

    tasks.register("copy$variantCapped") {
        dependsOn(copySoTask)
        dependsOn(copyDexTask)

        doLast {
            println("Dex and so files has been been copied to ${projectDir.asFile.resolve("out")}")
        }
    }
}

dependencies {
    compileOnly(projects.hiddenapi.stubs)
    implementation(projects.core)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.daemonService)
    implementation(projects.share.android)
    implementation(projects.share.java)

    implementation(libs.gson)
}