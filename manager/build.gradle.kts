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
        kotlinCompilerExtensionVersion = "1.5.13"
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

        task<Copy>("copy${variantCapped}Assets") {
            dependsOn(":meta-loader:copy$variantCapped")
            dependsOn(":patch-loader:copy$variantCapped")
            tasks["merge${variantCapped}Assets"].dependsOn(this)

            into("$buildDir/intermediates/assets/$variantLowered/merge${variantCapped}Assets")
            from("${rootProject.projectDir}/out/assets/${variant.name}")
        }

        task<Copy>("build$variantCapped") {
            dependsOn(tasks["assemble$variantCapped"])
            from(variant.outputs.map { it.outputFile })
            into("${rootProject.projectDir}/out/$variantLowered")
            rename(".*.apk", "NPatch-v$verName-$verCode-$variantLowered.apk")
        }
    }
}

dependencies {
    implementation(projects.patch)
    implementation(projects.services.daemonService)
    implementation(projects.share.android)
    implementation(projects.share.java)
    implementation(platform(npatch.androidx.compose.bom))

    annotationProcessor(npatch.androidx.room.compiler)
    compileOnly(npatch.rikka.hidden.stub)
    debugImplementation(npatch.androidx.compose.ui.tooling)
    debugImplementation(npatch.androidx.customview)
    debugImplementation(npatch.androidx.customview.poolingcontainer)
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
    ksp(npatch.androidx.room.compiler)
    ksp(npatch.raamcosta.compose.destinations.ksp)
}
