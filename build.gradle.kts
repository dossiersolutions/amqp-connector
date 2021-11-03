version = "0.3"

plugins {
    kotlin("plugin.serialization") version "1.5.21"
}

kotlin {
    jvm()
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.5.0")
                implementation("com.rabbitmq:amqp-client:5.13.1")
                implementation(project(":functional"))
                implementation(project(":dossier-stl"))
                implementation(project(":error-handling"))
            }
        }
    }
}