val apiCode = rootProject.extra["apiCode"] as Int
val verCode = rootProject.extra["verCode"] as Int
val verName = rootProject.extra["verName"] as String
val coreVerCode = rootProject.extra["coreVerCode"] as Int
val coreVerName = rootProject.extra["coreVerName"] as String
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion

plugins {
    id("java-library")
}

java {
    sourceCompatibility = androidSourceCompatibility
    targetCompatibility = androidTargetCompatibility
}

val generateTask = tasks.register<Copy>("generateJava") {
    val template = mapOf(
        "apiCode" to apiCode,
        "verCode" to verCode,
        "verName" to verName,
        "coreVerCode" to coreVerCode,
        "coreVerName" to coreVerName
    )
    inputs.properties(template)
    from("src/template/java")
    into(layout.buildDirectory.dir("generated/java"))
    expand(template)
}

sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/java"))
tasks.named("compileJava").configure {
    dependsOn(generateTask)
}
