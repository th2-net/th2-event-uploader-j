plugins {
    application
    kotlin("jvm") version "1.8.22"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.8.22"
    id("com.exactpro.th2.gradle.base") version "0.0.8"
}

group = "com.exactpro.th2"
version = project.findProperty("release_version") as String

repositories {
    mavenCentral()
    maven {
        name = "Sonatype_snapshots"
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }

    maven {
        name = "Sonatype_releases"
        url = uri("https://s01.oss.sonatype.org/content/repositories/releases/")
    }
}

dependencies {
    implementation("com.exactpro.th2:common:5.13.1-dev") {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
        exclude(group = "com.squareup.okhttp3", module = "logging-interceptor")
        exclude(group = "com.squareup.okio", module = "okio")
    }
    implementation("com.exactpro.th2:common-utils:2.2.3-dev")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    implementation("io.github.microutils:kotlin-logging:3.0.5")

    implementation("org.apache.commons:commons-lang3")
    implementation("commons-cli:commons-cli:1.7.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass = "com.exactpro.th2.uploader.event.AppKt"
}