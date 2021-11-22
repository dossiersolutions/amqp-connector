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
        val jvmTest by getting {
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
                implementation("org.junit.jupiter:junit-jupiter-params:5.8.1")
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
                implementation("org.testcontainers:rabbitmq:1.16.2")
                implementation("org.testcontainers:junit-jupiter:1.16.2")
            }
        }
        val jvmDoc by creating {
            kotlin.srcDir("src/jvmDoc/kotlin")
            dependsOn(jvmMain)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}