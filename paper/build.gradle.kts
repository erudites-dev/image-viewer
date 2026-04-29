plugins {
    id("multiloader-platform")
    id("java-library")
    id("io.papermc.paperweight.userdev") version("2.0.0-beta.21")
    id("xyz.jpenilla.run-paper") version("3.0.2")
}

base {
    archivesName = "imageviewer-paper"
}

val configurationCommonModJava: Configuration = configurations.create("commonJava") {
    isCanBeResolved = true
}
val configurationCommonModResources: Configuration = configurations.create("commonResources") {
    isCanBeResolved = true
}

dependencies {
    configurationCommonModJava(project(path = ":common", configuration = "commonMainJava"))
    configurationCommonModResources(project(path = ":common", configuration = "commonMainResources"))

    paperweight.paperDevBundle(BuildConfig.PAPER_VERSION)
}

sourceSets.apply {
    main {
        compileClasspath += configurationCommonModJava
        runtimeClasspath += configurationCommonModJava
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

    runServer {
        minecraftVersion(BuildConfig.MINECRAFT_VERSION)
    }
}
