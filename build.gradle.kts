plugins {
    val kotlinVersion = "1.9.22"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.github.shaksternano"
base.archivesName.set("jackbox-replay-downloader")
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    val ktorVersion = "2.3.7"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.slf4j:slf4j-nop:2.0.10")

    testImplementation(kotlin("test"))
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        manifest {
            attributes(
                mapOf(
                    "Main-Class" to "${project.group}.jackboxreplaydownloader.MainKt",
                )
            )
        }
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
}

kotlin {
    jvmToolchain(17)
}
