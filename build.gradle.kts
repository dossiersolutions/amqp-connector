import org.jetbrains.dokka.gradle.DokkaTaskPartial
import java.net.URL
import java.util.Base64

group = "no.dossier.libraries"
version = "0.1.6"

object Meta {
    const val desc = "RabbitMQ Kotlin Client library"
    const val license = "MIT"
    const val githubRepo = "dossiersolutions/amqp-connector"
    const val releaseRepoUrl = "https://s01.oss.sonatype.org/service/local/"
    const val snapshotRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
}

fun MavenPom.commonMetadata() {
    name.set(project.name)
    description.set(Meta.desc)
    url.set("https://github.com/${Meta.githubRepo}")
    licenses {
        license {
            name.set(Meta.license)
            url.set("https://opensource.org/licenses/MIT")
        }
    }
    developers {
        developer {
            id.set("kubapet")
            name.set("Jakub Petrzilka")
            organization.set("Dossier Solutions")
            organizationUrl.set("https://dossier.no/")
        }
    }
    scm {
        url.set(
            "https://github.com/${Meta.githubRepo}.git"
        )
        connection.set(
            "scm:git:git://github.com/${Meta.githubRepo}.git"
        )
        developerConnection.set(
            "scm:git:git://github.com/${Meta.githubRepo}.git"
        )
    }
    issueManagement {
        url.set("https://github.com/${Meta.githubRepo}/issues")
    }
}

repositories {
    mavenCentral()
}

plugins {
    id("org.gradle.maven-publish")
    id("org.gradle.signing")
    kotlin("multiplatform") version "1.6.0"
    id("org.jetbrains.dokka") version "1.6.0"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    kotlin("plugin.serialization") version "1.5.21"
}

kotlin {
    jvm()
    sourceSets {
        val commonMain by existing {
            dependencies {
                implementation("no.dossier.libraries:functional:0.1.1")
                implementation("no.dossier.libraries:error-handling:0.1.0")
            }
        }
        val jvmMain by existing {
            dependencies {
                implementation("io.github.microutils:kotlin-logging-jvm:2.0.11")
                implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.5.0")
                implementation("com.rabbitmq:amqp-client:5.14.0")
            }
        }
        val jvmTest by existing {
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
            dependsOn(jvmMain.get())
        }
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }
    withType<DokkaTaskPartial>().configureEach {
        dokkaSourceSets {
            removeIf { it.name == "jvmDoc" }
            named("jvmMain") {
                includes.from("${projectDir}/src/jvmDoc/packages.md")
                includes.from("${projectDir}/src/jvmDoc/module.md")
                suppressObviousFunctions.set(true)
                suppressInheritedMembers.set(true)
                samples.from("${projectDir}/src/jvmDoc/kotlin")
                if (file("${projectDir}/src/jvmMain").exists()) {
                    sourceLink {
                        localDirectory.set(file("src/jvmMain/kotlin"))
                        remoteUrl.set(
                            URL(
                                "https://github.com/dossiersolutions/${projectDir.name}/src/jvmMain/kotlin"
                            )
                        )
                        remoteLineSuffix.set("#lines-")
                    }
                }
            }
        }
    }
    val dokkaHtml by existing
    val javadocKotlinMultiplatformJar by registering(Jar::class) {
        group = JavaBasePlugin.DOCUMENTATION_GROUP
        description = "Assembles Javadoc JAR for KotlinMultiplatform publication"
        archiveClassifier.set("javadoc")
        archiveAppendix.set("")
        from(dokkaHtml.get())
    }
    val javadocJvmJar by registering(Jar::class) {
        group = JavaBasePlugin.DOCUMENTATION_GROUP
        description = "Assembles Javadoc JAR for JVM publication"
        archiveClassifier.set("javadoc")
        archiveAppendix.set("jvm")
        from(dokkaHtml.get())
    }
    val publish by existing {
        dependsOn(javadocKotlinMultiplatformJar, javadocJvmJar)
    }
}

signing {
    val signingKey = System.getenv("GPG_SIGNING_KEY")?.let { String(Base64.getDecoder().decode(it)) }
    val signingPassphrase = System.getenv("GPG_SIGNING_PASSPHRASE")

    useInMemoryPgpKeys(signingKey, signingPassphrase)
    val extension = extensions.getByName("publishing") as PublishingExtension
    sign(extension.publications)
}


publishing {
    publications {
        val jvm by existing(MavenPublication::class) {
            artifact(tasks["javadocJvmJar"])
            pom {
                commonMetadata()
            }
        }
        val kotlinMultiplatform by existing(MavenPublication::class) {
            artifact(tasks["javadocKotlinMultiplatformJar"])
            pom {
                commonMetadata()
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri(Meta.releaseRepoUrl))
            snapshotRepositoryUrl.set(uri(Meta.snapshotRepoUrl))
            val ossrhUsername = System.getenv("OSSRH_USERNAME")
            val ossrhPassword = System.getenv("OSSRH_PASSWORD")
            username.set(ossrhUsername)
            password.set(ossrhPassword)
        }
    }
}