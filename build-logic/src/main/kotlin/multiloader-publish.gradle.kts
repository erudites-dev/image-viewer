plugins {
    id("me.modmuss50.mod-publish-plugin")
}

val projectProviders = providers

gradle.projectsEvaluated {
    // https://github.com/modmuss50/mod-publish-plugin
    publishMods {
        //dryRun = true

        val changelogEnv = projectProviders.environmentVariable("CHANGELOG_TEXT").orNull
        changelog = if (!changelogEnv.isNullOrEmpty()) {
            changelogEnv
        } else {
            projectProviders.fileContents(layout.projectDirectory.file("changelog.md")).asText.get()
        }

        val versionType = projectProviders.environmentVariable("VERSION_TYPE").orElse("release").get()
        type = when (versionType) {
            "alpha" -> ALPHA
            "beta" -> BETA
            else -> STABLE
        }
        val includeSnapshot = projectProviders.environmentVariable("INCLUDE_SNAPSHOTS").orElse("false").get().toBoolean()
        val publishEnabled = projectProviders.environmentVariable("PUBLISH_ENABLED").orElse("true").get().toBoolean()
        val fabricEnabled = projectProviders.environmentVariable("FABRIC_ARTIFACT").orElse("true").get().toBoolean()
        val neoforgeEnabled = projectProviders.environmentVariable("NEOFORGE_ARTIFACT").orElse("true").get().toBoolean()
        val paperEnabled = projectProviders.environmentVariable("PAPER_ARTIFACT").orElse("true").get().toBoolean()
        val modrinthEnabled = projectProviders.environmentVariable("PUBLISH_MODRINTH").orElse("true").get().toBoolean()
        val curseforgeEnabled = projectProviders.environmentVariable("PUBLISH_CURSEFORGE").orElse("true").get().toBoolean()

        if (!publishEnabled) {
            println("Publishing is disabled. Set PUBLISH_ENABLED=true to enable.")
            return@publishMods
        }

        val cfOptions = curseforgeOptions {
            accessToken = projectProviders.environmentVariable("CURSEFORGE_TOKEN")
            projectId = "1527636"
            minecraftVersionRange {
                start = BuildConfig.MINECRAFT_VERSION_MIN
                end = "latest"
            }
            clientRequired = true
            serverRequired = true
            javaVersions.add(JavaVersion.toVersion(BuildConfig.JAVA_VERSION))
            requires("fabric-api")
            embeds("mcef-keksuccino")
        }

        val mrOptions = modrinthOptions {
            accessToken = projectProviders.environmentVariable("MODRINTH_TOKEN")
            projectId = "GO2oCTbO"
            minecraftVersionRange {
                start = BuildConfig.MINECRAFT_VERSION_MIN
                end = "latest"
                includeSnapshots = includeSnapshot
            }
            requires("fabric-api")
            embeds("mcef-keksuccino")
        }

        val minecraftVersion = BuildConfig.MINECRAFT_VERSION.substringBefore('-')
        val fabricJar = project(":fabric").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val neoforgeJar = project(":neoforge").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val paperJar = project(":paper").tasks.named<Jar>("jar").flatMap { it.archiveFile }

        // Fabric
        if (fabricEnabled) {
            if (curseforgeEnabled) {
                curseforge("curseforgeFabric") {
                    from(cfOptions)
                    file = fabricJar
                    modLoaders.add("fabric")
                    modLoaders.add("quilt")
                    version = "mc${minecraftVersion}-${BuildConfig.MOD_VERSION}-fabric"
                    displayName = "Image Viewer ${BuildConfig.MOD_VERSION} for Fabric ${BuildConfig.MINECRAFT_VERSION}"
                }
            }

            if (modrinthEnabled) {
                modrinth("modrinthFabric") {
                    from(mrOptions)
                    file = fabricJar
                    modLoaders.add("fabric")
                    modLoaders.add("quilt")
                    version = "mc${minecraftVersion}-${BuildConfig.MOD_VERSION}-fabric"
                    displayName = "Image Viewer ${BuildConfig.MOD_VERSION} for Fabric ${BuildConfig.MINECRAFT_VERSION}"
                }
            }
        }

        // NeoForge
        if (neoforgeEnabled) {
            if (curseforgeEnabled) {
                curseforge("curseforgeNeoforge") {
                    from(cfOptions)
                    file = neoforgeJar
                    modLoaders.add("neoforge")
                    version = "mc${minecraftVersion}-${BuildConfig.MOD_VERSION}-neoforge"
                    displayName = "Image Viewer ${BuildConfig.MOD_VERSION} for NeoForge ${BuildConfig.MINECRAFT_VERSION}"
                }
            }

            if (modrinthEnabled) {
                modrinth("modrinthNeoforge") {
                    from(mrOptions)
                    file = neoforgeJar
                    modLoaders.add("neoforge")
                    version = "mc${minecraftVersion}-${BuildConfig.MOD_VERSION}-neoforge"
                    displayName = "Image Viewer ${BuildConfig.MOD_VERSION} for NeoForge ${BuildConfig.MINECRAFT_VERSION}"
                }
            }
        }

        // Paper
        if (paperEnabled) {
            if (modrinthEnabled) {
                modrinth("modrinthPaper") {
                    from(mrOptions)
                    file = paperJar
                    modLoaders.add("paper")
                    version = "mc${minecraftVersion}-${BuildConfig.MOD_VERSION}-paper"
                    displayName = "Image Viewer ${BuildConfig.MOD_VERSION} for Paper ${BuildConfig.MINECRAFT_VERSION}"
                }
            }
        }

        // GitHub Release
        github {
            accessToken = projectProviders.environmentVariable("GITHUB_TOKEN")
            repository = "erudites-dev/image-viewer"
            commitish = "main"
            tagName = "${BuildConfig.MOD_VERSION}+mc${BuildConfig.MINECRAFT_VERSION}"
            version = "${BuildConfig.MOD_VERSION}+mc${BuildConfig.MINECRAFT_VERSION}"
            displayName = "Image Viewer ${BuildConfig.MOD_VERSION} for Minecraft ${BuildConfig.MINECRAFT_VERSION}"

            val jars = listOfNotNull(
                fabricJar.takeIf { fabricEnabled },
                neoforgeJar.takeIf { neoforgeEnabled },
                paperJar.takeIf { paperEnabled },
            )
            jars.firstOrNull()?.let { file = it }
            jars.drop(1).forEach { additionalFiles.from(it) }
        }
    }

    val allPublishTasks = tasks.withType<me.modmuss50.mpp.PublishModTask>()
    val fabricTasks = allPublishTasks.matching { it.name.contains("fabric", ignoreCase = true) }
    val neoforgeTasks = allPublishTasks.matching { it.name.contains("neoforge", ignoreCase = true) }
    val paperTasks = allPublishTasks.matching { it.name.contains("paper", ignoreCase = true) }
    neoforgeTasks.configureEach {
        mustRunAfter(fabricTasks)
    }
    paperTasks.configureEach {
        mustRunAfter(fabricTasks)
        mustRunAfter(neoforgeTasks)
    }
}
