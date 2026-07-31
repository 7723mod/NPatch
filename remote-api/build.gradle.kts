import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "top.nkbe.npatch.remote"

    buildFeatures {
        androidResources = false
        buildConfig = false
    }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        val variantCapped = variantName.replaceFirstChar { it.uppercase() }
        val verName: String by rootProject.extra
        val verCode: Int by rootProject.extra

        tasks.register<Copy>("build$variantCapped") {
            dependsOn("assemble$variantCapped")
            from(variant.artifacts.get(SingleArtifact.AAR))
            into(rootProject.layout.projectDirectory.dir("out/$variantName"))
            rename(
                ".*\\.aar",
                "npatch-remote-api-v$verName-$verCode-$variantName.aar",
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:interface:102.0.0")
}
