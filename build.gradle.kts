import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL
import java.util.Base64

group = "no.dossier.libraries"
version = "0.2.8"

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
    kotlin("multiplatform") version "1.9.20"
    id("org.jetbrains.dokka") version "1.9.10"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    kotlin("plugin.serialization") version "1.9.20"
}

kotlin {
    jvm()
    /*linuxX64("native") {
        binaries {
            sharedLib {
                baseName = "native"
            }
        }
    }*/
    sourceSets {
        val commonMain by existing {
            dependencies {
                implementation("no.dossier.libraries:functional:0.2.4")
                implementation("no.dossier.libraries:error-handling:0.1.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("io.github.microutils:kotlin-logging:2.0.11")
                implementation("org.jetbrains.kotlin:kotlin-stdlib")
            }
        }
        /*val nativeMain by existing {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-linuxx64:1.6.4")
            }
        }*/
        val jvmMain by existing {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
                implementation("com.rabbitmq:amqp-client:5.20.0")
                implementation("io.github.microutils:kotlin-logging-jvm:2.0.11")
            }
        }
        val jvmTest by existing {
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
                implementation("org.junit.jupiter:junit-jupiter-params:5.8.1")
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
                runtimeOnly("ch.qos.logback:logback-classic:1.2.11")
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
    withType<KotlinCompile> { kotlinOptions.jvmTarget = "21" }
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
    /*val javadocNativeJar by registering(Jar::class) {
        group = JavaBasePlugin.DOCUMENTATION_GROUP
        description = "Assembles Javadoc JAR for Native publication"
        archiveClassifier.set("javadoc")
        archiveAppendix.set("native")
        from(dokkaHtml.get())
    }*/
    val javadocJvmJar by registering(Jar::class) {
        group = JavaBasePlugin.DOCUMENTATION_GROUP
        description = "Assembles Javadoc JAR for JVM publication"
        archiveClassifier.set("javadoc")
        archiveAppendix.set("jvm")
        from(dokkaHtml.get())
    }
    val publish by existing {
        dependsOn(javadocKotlinMultiplatformJar, javadocJvmJar/*, javadocNativeJar*/)
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
    repositories {
        maven {
            name = "DossierNexus"
            url = uri("https://devsrv.dossier.no/nexus/repository/maven-releases/")
            credentials {
                // Read nexus username and password from env (CI) or ~/.gradle/gradle.properties
                username = System.getenv("MAVEN_REPO_USERNAME") ?: project.property("nexusUsername")!!.toString()
                password = System.getenv("MAVEN_REPO_PASSWORD") ?: project.property("nexusPassword")!!.toString()
            }
            metadataSources {
                mavenPom()
                artifact()
            }
        }
    }
    publications {
        /*val native by existing(MavenPublication::class) {
            artifact(tasks["javadocNativeJar"])
            pom {
                commonMetadata()
            }
        }*/
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
