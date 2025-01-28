import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    mavenCentral()

    maven("https://jitpack.io")
}

group   = "one.wabbit"
version = "2.0.0"

plugins {
    kotlin("jvm") version "2.0.20"

    kotlin("plugin.serialization") version "2.0.20"

    id("maven-publish")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "one.wabbit"
            artifactId = "kotlin-fastapi"
            version = "2.0.0"
            from(components["java"])
        }
    }
}

dependencies {
    implementation("com.github.wabbit-corp:kotlin-minilog:1.0.2")
    implementation("com.github.wabbit-corp:kotlin-parsing-parsers:2.0.0")
    implementation("com.github.wabbit-corp:kotlin-parsing-charset:1.2.0")
    implementation("com.github.wabbit-corp:kotlin-exception-serialization:1.1.0")

    testImplementation(kotlin("test"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")

    implementation("it.unimi.dsi:fastutil:8.5.15")
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-tree:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation("org.ow2.asm:asm-analysis:9.7.1")
    implementation("org.slf4j:slf4j-log4j12:2.0.16")
    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-netty:2.3.13")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-server-status-pages:2.3.13")
    implementation("io.ktor:ktor-server-call-logging:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("io.ktor:ktor-server-auth:2.3.13")
    implementation("io.ktor:ktor-server-auth-jwt:2.3.13")
    implementation("io.ktor:ktor-server-cors:2.3.13")
    implementation("io.ktor:ktor-server-websockets:2.3.13")
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-client-serialization:2.3.13")
    implementation("io.ktor:ktor-client-auth:2.3.13")
    implementation("io.ktor:ktor-server-tests-jvm:2.3.13")
}

java {
    targetCompatibility = JavaVersion.toVersion(21)
    sourceCompatibility = JavaVersion.toVersion(21)
}

tasks {
    withType<Test> {
        jvmArgs("-ea")

    }
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }
    withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xcontext-receivers")
        }
    }

    jar {
        setProperty("zip64", true)

    }
}