plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "org.lsposed.npatch.share"

    buildFeatures {
        androidResources = false
        buildConfig = false
    }
}

dependencies {
    implementation(projects.services.daemonService)
}
