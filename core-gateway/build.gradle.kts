plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-common"))
    api(project(":core-llm"))
    api(project(":core-tools"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
