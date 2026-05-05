import java.util.Locale

plugins {
    alias(libs.plugins.agp.app)
}

android {
    defaultConfig {
        multiDexEnabled = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }
    namespace = "top.nkbe.npatch.metaloader"
}

androidComponents.onVariants { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
    val variantLowered = variant.name.lowercase()
    val buildDirProvider = layout.buildDirectory
    val dexDirProvider = if (variant.buildType == "release") {
        buildDirProvider.dir("intermediates/dex/$variantLowered/minify${variantCapped}WithR8")
    } else {
        buildDirProvider.dir("intermediates/dex/$variantLowered/mergeDex$variantCapped")
    }
    val copyDestination = rootProject.layout.projectDirectory.dir("out/assets/${variant.name}/npatch")

    val copyDexTask = tasks.register<Copy>("copyDex$variantCapped") {
        dependsOn("assemble$variantCapped")
        from(dexDirProvider)
        rename("classes.dex", "metaloader.dex")
        into(copyDestination)
    }

    tasks.register("copy$variantCapped") {
        dependsOn(copyDexTask)
        doLast {
            println("Loader dex has been copied to ${rootProject.projectDir}${File.separator}out")
        }
    }
}

dependencies {
    compileOnly("vector:stubs")
    implementation(projects.share.java)
    implementation(libs.hiddenapibypass)
}
