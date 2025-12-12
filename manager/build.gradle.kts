import java.util.Locale

val defaultManagerPackageName: String by rootProject.extra
val apiCode: Int by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val coreVerCode: Int by rootProject.extra
val coreVerName: String by rootProject.extra

plugins {
    alias(libs.plugins.agp.app)
    alias(npatch.plugins.compose.compiler)
    alias(npatch.plugins.google.devtools.ksp)
    alias(npatch.plugins.rikka.tools.refine)
    alias(npatch.plugins.kotlin.android)
    id("kotlin-parcelize")
}

android {
    defaultConfig {
        applicationId = defaultManagerPackageName
    }

    androidResources {
        noCompress.add(".so")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        all {
            sourceSets[name].assets.srcDirs(rootProject.projectDir.resolve("out/assets/$name"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    namespace = "org.lsposed.npatch"

    applicationVariants.all {
        kotlin.sourceSets {
            getByName(name) {
                kotlin.srcDir("build/generated/ksp/$name/kotlin")
            }
        }
    }
}

afterEvaluate {
    android.applicationVariants.forEach { variant ->
        val variantLowered = variant.name.lowercase()
        val variantCapped = variant.name.replaceFirstChar { it.uppercase() }

        val copyAssetsTaskProvider = tasks.register<Copy>("copy${variantCapped}Assets") {
            dependsOn(":meta-loader:copy$variantCapped")
            dependsOn(":patch-loader:copy$variantCapped")

            val targetDir = layout.buildDirectory.dir("intermediates/assets/$variantLowered/merge${variantCapped}Assets")
            into(targetDir)

            from("${rootProject.projectDir}/out/assets/${variant.name}")
        }

        tasks.named("merge${variantCapped}Assets").configure {
            dependsOn(copyAssetsTaskProvider)
        }

        tasks.register<Copy>("build$variantCapped") {
            dependsOn("assemble$variantCapped")
            from(variant.outputs.map { it.outputFile })
            into("${rootProject.projectDir}/out/$variantLowered")
            rename(".*.apk", "NPatch-v$verName-$verCode-$variantLowered.apk")
        }
    }
}

dependencies {
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")

    implementation(projects.patch)
    implementation(projects.services.daemonService)
    implementation(projects.share.android)
    implementation(projects.share.java)

    implementation(platform(npatch.androidx.compose.bom))
    implementation(npatch.androidx.activity.compose)
    implementation(npatch.androidx.compose.material.icons.extended)
    implementation(npatch.androidx.compose.material3)
    implementation(npatch.androidx.compose.ui)
    implementation(npatch.androidx.compose.ui.tooling.preview)
    implementation(npatch.androidx.core.ktx)
    implementation(npatch.androidx.lifecycle.viewmodel.compose)
    implementation(npatch.androidx.navigation.compose)
    implementation(libs.androidx.preference)
    implementation(npatch.androidx.room.ktx)
    implementation(npatch.androidx.room.runtime)

    implementation(npatch.google.accompanist.navigation.animation)
    implementation(npatch.google.accompanist.pager)
    implementation(npatch.google.accompanist.swiperefresh)
    implementation(libs.material)
    implementation(libs.gson)
    implementation(npatch.rikka.shizuku.api)
    implementation(npatch.rikka.shizuku.provider)
    implementation(npatch.rikka.refine)
    implementation(npatch.raamcosta.compose.destinations)
    implementation(libs.appiconloader)
    implementation(libs.hiddenapibypass)

    annotationProcessor(npatch.androidx.room.compiler)
    compileOnly(npatch.rikka.hidden.stub)
    ksp(npatch.androidx.room.compiler)
    ksp(npatch.raamcosta.compose.destinations.ksp)

    debugImplementation(npatch.androidx.compose.ui.tooling)
    debugImplementation(npatch.androidx.customview)
    debugImplementation(npatch.androidx.customview.poolingcontainer)
}