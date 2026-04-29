plugins {
    id("multiloader-platform")
    id("net.fabricmc.fabric-loom") version("1.16.+")
}

base {
    archivesName = "imageviewer-fabric"
}

val configurationCommonModJava: Configuration = configurations.create("commonJava") {
    isCanBeResolved = true
}
val configurationCommonModResources: Configuration = configurations.create("commonResources") {
    isCanBeResolved = true
}

repositories {
    maven("https://maven.terraformersmc.com/releases/")
}

dependencies {
    configurationCommonModJava(project(path = ":common", configuration = "commonMainJava"))
    configurationCommonModResources(project(path = ":common", configuration = "commonMainResources"))
}

sourceSets.apply {
    main {
        compileClasspath += configurationCommonModJava
        runtimeClasspath += configurationCommonModJava
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${BuildConfig.MINECRAFT_VERSION}")
    implementation("net.fabricmc:fabric-loader:${BuildConfig.FABRIC_LOADER_VERSION}")
    implementation("net.fabricmc.fabric-api:fabric-api:${BuildConfig.FABRIC_API_VERSION}")

    implementation(include("de.keksuccino:mcef-fabric:${BuildConfig.MCEF_VERSION}")!!)
}

loom {
    mixin {
        useLegacyMixinAp = false
    }

    runs {
        named("client") {
            client()
            configName = "Fabric/Client"
            appendProjectPathToConfigName = false
            ideConfigGenerated(true)
            runDir("run")
        }
    }
}

tasks {
    val outputDir = rootProject.layout.buildDirectory.dir("output")

    jar {
        from(configurationCommonModJava)
        destinationDirectory.set(outputDir)
    }

    sourcesJar {
        from(configurationCommonModJava)
        destinationDirectory.set(outputDir.map { it.dir("sources") })
    }

    processResources {
        from(configurationCommonModResources)
    }
}
