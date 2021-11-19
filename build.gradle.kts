version = "0.16"

plugins {
    kotlin("plugin.serialization") version "1.5.21"
}

kotlin {
    jvm()
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation("io.github.microutils:kotlin-logging-jvm:2.0.11")
                implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.5.0")
                implementation("com.rabbitmq:amqp-client:5.14.0")
                implementation(project(":functional"))
                implementation(project(":dossier-stl"))
                implementation(project(":error-handling"))
            }
        }
        val jvmDoc by creating {
            kotlin.srcDir("src/jvmDoc/kotlin")
            dependsOn(jvmMain)
        }
    }
}