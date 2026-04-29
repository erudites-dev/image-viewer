pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://repo.papermc.io/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "image-viewer"

includeBuild("build-logic")
include("common")
include("fabric")
include("neoforge")
include("paper")
