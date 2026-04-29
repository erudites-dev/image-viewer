plugins {
    id("multiloader-platform")
    id("net.neoforged.moddev") version("2.0.+")
}

base {
    archivesName = "imageviewer-neoforge"
}

val configurationCommonModJava: Configuration = configurations.create("commonModJava") {
    isCanBeResolved = true
}
val configurationCommonModResources: Configuration = configurations.create("commonModResources") {
    isCanBeResolved = true
}

repositories {
    maven("https://maven.neoforged.net/releases/")
}

dependencies {
    configurationCommonModJava(project(path = ":common", configuration = "commonMainJava"))
    configurationCommonModResources(project(path = ":common", configuration = "commonMainResources"))

    implementation(jarJar("de.keksuccino:mcef-neoforge:${BuildConfig.MCEF_VERSION}")!!)
}

sourceSets {
    main {
        compileClasspath += configurationCommonModJava
        runtimeClasspath += configurationCommonModJava
    }
}

neoForge {
    version = BuildConfig.NEOFORGE_VERSION

    runs {
        create("client") {
            client()
        }

        create("server") {
            server()
            programArguments.addAll("--nogui")
        }
    }

    mods {
        create("imageviewer") {
            sourceSet(sourceSets["main"])
            sourceSet(project(":common").sourceSets["main"])
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
