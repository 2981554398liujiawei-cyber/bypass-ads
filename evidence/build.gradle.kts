plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("app.bypassads.evidence.JsonlTraceValidatorRunnerKt")
}

tasks.test {
    useJUnitPlatform()
}
