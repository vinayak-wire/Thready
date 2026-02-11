plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.wire:wire-apps-jvm-sdk:0.0.19")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test"))
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}


kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}
