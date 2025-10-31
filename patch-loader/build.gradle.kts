import java.util.Locale

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

androidComponents.onVariants { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }

    val copyDexTask = tasks.register<Copy>("copyDex$variantCapped") {
        dependsOn("assemble$variantCapped")
        from(layout.buildDirectory.file("intermediates/dex/${variant.name}/mergeDex$variantCapped/classes.dex"))
        rename("classes.dex", "loader.dex")
        into("${rootProject.projectDir}/out/assets/${variant.name}/lspatch")
    }

    val copySoTask = tasks.register<Copy>("copySo$variantCapped") {
        dependsOn("assemble$variantCapped")
        dependsOn("strip${variantCapped}DebugSymbols")
        val libDir = variant.name + "/strip${variantCapped}DebugSymbols"
        from(
            fileTree(
                "dir" to layout.buildDirectory.dir("intermediates/stripped_native_libs/${variant.name}/strip${variantCapped}DebugSymbols/out/lib"),
                "include" to listOf("**/liblspatch.so")
            )
        )
        into("${rootProject.projectDir}/out/assets/${variant.name}/lspatch/so")
    }

    tasks.register("copy$variantCapped") {
        dependsOn(copySoTask)
        dependsOn(copyDexTask)

        doLast {
            println("Dex and so files has been copied to ${rootProject.projectDir}${File.separator}out")
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
