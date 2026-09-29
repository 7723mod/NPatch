val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion

plugins {
    id("java-library")
    alias(npatch.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = androidSourceCompatibility
    targetCompatibility = androidTargetCompatibility
    sourceSets {
        main {
            java.srcDirs("libs/manifest-editor/lib/src/main/java")
            resources.srcDirs("libs/manifest-editor/lib/src/main")
        }
    }
}

dependencies {
    implementation(projects.share.java)
    implementation("top.nkbe:NeoApk:1.0.1")
    implementation("vector:axml")

    implementation(npatch.commons.io)
    implementation(npatch.beust.jcommander)
    implementation(npatch.google.gson)
    testImplementation("junit:junit:4.13.2")
}
