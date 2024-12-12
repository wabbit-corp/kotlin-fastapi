rootProject.name = "kotlin-fastapi"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://papermc.io/repo/repository/maven-public/")
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://papermc.io/repo/repository/maven-public/")
    }
}