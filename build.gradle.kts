import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    mavenCentral()
}

group   = "one.wabbit"
version = "2.0.0"

plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")

    kotlin("plugin.serialization")

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
    implementation(project(":kotlin-minilog")) // 1.0.2
    implementation(project(":kotlin-parsing-parsers")) // 3.0.0
    implementation(project(":kotlin-parsing-charset")) // 1.3.0
    implementation(project(":kotlin-exception-serialization")) // 1.1.0
    implementation(project(":kotlin-exec")) // 0.0.1

    testImplementation(kotlin("test"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")

    implementation("it.unimi.dsi:fastutil:8.5.16")
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    implementation("org.ow2.asm:asm-util:9.8")
    implementation("org.ow2.asm:asm-analysis:9.8")
    implementation("org.slf4j:slf4j-log4j12:2.0.17")
    implementation("io.ktor:ktor-server-core:3.3.0")
    implementation("io.ktor:ktor-server-netty:3.3.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.0")
    implementation("io.ktor:ktor-server-status-pages:3.3.0")
    implementation("io.ktor:ktor-server-call-logging:3.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0")
    implementation("io.ktor:ktor-server-auth:3.3.0")
    implementation("io.ktor:ktor-server-auth-jwt:3.3.0")
    implementation("io.ktor:ktor-server-cors:3.3.0")
    implementation("io.ktor:ktor-server-websockets:3.3.0")
    implementation("io.ktor:ktor-client-core:3.3.0")
    implementation("io.ktor:ktor-client-cio:3.3.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.0")
    implementation("io.ktor:ktor-client-serialization:3.3.0")
    implementation("io.ktor:ktor-client-auth:3.3.0")
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

            freeCompilerArgs.add("-Xcontext-parameters")

        }
    }

    jar {
        setProperty("zip64", true)

    }
}

// Kover Configuration
kover {
    // useJacoco() // This is the default, can be specified if you want to be explicit
    // reports {
    //     // Configure reports for the default test task.
    //     // Kover tries to infer the variant for simple JVM projects.
    //     // If you have specific build types/flavors, you'd configure them here as variants.
    //     variant() { // Or remove "debug" for a default JVM setup unless you have variants
    //         html {
    //             // reportDir.set(layout.buildDirectory.dir("reports/kover/html")) // Uncomment to customize output
    //             // title.set("kotlin-fastapi Code Coverage") // Uncomment to customize title
    //         }
    //         xml {
    //             // reportFile.set(layout.buildDirectory.file("reports/kover/coverage.xml")) // Uncomment to customize output
    //         }
    //     }
    // }
}

dokka {
    moduleName.set("kotlin-fastapi")
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }
    dokkaSourceSets.main {
        // includes.from("README.md")

        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/wabbit-corp/kotlin-fastapi/tree/master/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }

    }
    pluginsConfiguration.html {
        // customStyleSheets.from("styles.css")
        // customAssets.from("logo.png")
        footerMessage.set("(c) Wabbit Consulting Corporation")
    }
}
