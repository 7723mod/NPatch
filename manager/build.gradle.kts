import java.util.Base64
import java.util.Locale
import com.android.build.api.artifact.SingleArtifact

val defaultManagerPackageName: String by rootProject.extra
val apiCode: Int by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val coreVerCode: Int by rootProject.extra
val coreVerName: String by rootProject.extra
val couiVersion = npatch.versions.coui.get()

fun decodeSha256Hex(value: String): ByteArray {
    require(value.length == 64) { "Manager signature digest must be 64 hex chars: $value" }
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun encodeAllowlistEntry(value: String): String {
    val key = 0x5A
    val obfuscated = decodeSha256Hex(value).map { byte -> (byte.toInt() xor key).toByte() }.toByteArray()
    return Base64.getEncoder().encodeToString(obfuscated)
}

plugins {
    alias(libs.plugins.agp.app)
    alias(npatch.plugins.kotlin.android)
    alias(npatch.plugins.compose.compiler)
    alias(npatch.plugins.google.devtools.ksp)
    alias(npatch.plugins.rikka.tools.refine)
    id("kotlin-parcelize")
}

android {
    defaultConfig {
        applicationId = defaultManagerPackageName
        val managerSignatureAllowlist = (
            System.getenv("NPATCH_MANAGER_SIGNATURE_SHA256")
                ?: project.findProperty("npatchManagerSignatureSha256")?.toString()
                ?: listOf(
                    "DB73788534AFFC4BFA3AE16040A2D3A2",
                    "C2B63EDEA1E07F3A1CF9AFF4DD0995A8",
                ).joinToString("")
            )
            .split(',', ';', ' ', '\n', '\r', '\t')
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",") { encodeAllowlistEntry(it) }
        buildConfigField("String", "MANAGER_SIGNATURE_SHA256_ALLOWLIST", "\"$managerSignatureAllowlist\"")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            excludes += "lib/*/libandroidx.graphics.path.so"
            excludes += "lib/*/libdatastore_shared_counter.so"
        }
        resources {
            excludes += "kotlin/**"
            excludes += "META-INF/androidx*"
            excludes += "META-INF/androidx/**"
            excludes += "DebugProbesKt.bin"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // 启用 R8/ProGuard 进行代码压缩、优化和混淆。
            isShrinkResources = true    // 启用资源缩减，移除未被引用的资源文件。
            isDebuggable = false        // 发布版本禁止调试。
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
        aidl = true
        compose = true
        buildConfig = true
    }

    namespace = "top.nkbe.npatch"

}

androidComponents {
    onVariants { variant ->
        val variantLowered = variant.name.lowercase()
        val variantCapped = variant.name.replaceFirstChar { it.uppercase() }

        val copyAssetsTaskProvider = tasks.register<Copy>("copy${variantCapped}Assets") {
            dependsOn(":meta-loader:copy$variantCapped")
            dependsOn(":patch-loader:copy$variantCapped")

            val targetDir = layout.buildDirectory.dir("intermediates/assets/$variantLowered/merge${variantCapped}Assets")
            doFirst {
                delete(targetDir.map { it.file("npatch/loader.dex") })
            }
            into(targetDir)

            from("${rootProject.projectDir}/out/assets/${variant.name}")
        }

        tasks.configureEach {
            if (name == "merge${variantCapped}Assets") {
                dependsOn(copyAssetsTaskProvider)
            }
        }

        tasks.register<Copy>("build$variantCapped") {
            dependsOn("assemble$variantCapped")
            from(variant.artifacts.get(SingleArtifact.APK))
            into("${rootProject.projectDir}/out/$variantLowered")
            rename(".*.apk", "NPatch-v$verName-$verCode-$variantLowered.apk")
        }
    }
}

dependencies {
    implementation(projects.patch)
    implementation(projects.share.android)
    implementation(projects.share.java)
    implementation("vector:daemon-service")

    implementation(platform(npatch.androidx.compose.bom))
    implementation(npatch.androidx.activity.compose)
    implementation(npatch.androidx.compose.material.icons.extended)
    implementation(npatch.androidx.compose.material3)
    implementation(npatch.androidx.compose.ui)
    implementation(npatch.androidx.compose.ui.tooling.preview)
    implementation(npatch.androidx.core.ktx)
    implementation("com.google.android.material:material:1.12.0")
    implementation(npatch.androidx.datastore.preferences)
    implementation(npatch.coil.compose)
    implementation(libs.gson)
    implementation(npatch.androidx.lifecycle.viewmodel.compose)
    implementation(npatch.androidx.navigation3.runtime)
    implementation(npatch.androidx.navigation3.ui)
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation(npatch.androidx.room.ktx)
    implementation(npatch.androidx.room.runtime)
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2")

    implementation(libs.gson)
    implementation(npatch.rikka.shizuku.api)
    implementation(npatch.rikka.shizuku.provider)
    implementation(npatch.rikka.refine)
    //implementation(npatch.raamcosta.compose.destinations)
    implementation("me.zhanghai.android.appiconloader:appiconloader:1.5.0")
    implementation(npatch.hiddenapibypass)

    // COUI & Haze
    implementation(npatch.haze)
    implementation(npatch.hazeBlur)
    implementation(npatch.backdrop)
    implementation("io.github.suqi8.coui.kmp:coui-ui:$couiVersion")
    implementation("io.github.suqi8.coui.kmp:coui-preference:$couiVersion")
    implementation("io.github.suqi8.coui.kmp:coui-icons:$couiVersion")
    implementation(npatch.androidx.webkit)


    annotationProcessor(npatch.androidx.room.compiler)
    compileOnly(npatch.rikka.hidden.stub)
    ksp(npatch.androidx.room.compiler)
    //ksp(npatch.raamcosta.compose.destinations.ksp)

    debugImplementation(npatch.androidx.compose.ui.tooling)
    debugImplementation(npatch.androidx.customview)
    debugImplementation(npatch.androidx.customview.poolingcontainer)
}
