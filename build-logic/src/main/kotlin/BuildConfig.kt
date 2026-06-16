import org.gradle.api.Project

object BuildConfig {
    val JAVA_VERSION: Int = 25

    val MINECRAFT_VERSION_RANGE: String = ">=26.2" // range: ">=26.1 <27.1"
    val MINECRAFT_VERSION_MIN: String = MINECRAFT_VERSION_RANGE.split(" ")[0].replace(Regex("^[><=!\\[\\]()]+"), "")
    val MINECRAFT_VERSION: String = "26.2"
    val NEOFORGE_VERSION: String = "26.2.0.0-beta"
    val FABRIC_LOADER_VERSION: String = "0.19.2"
    val FABRIC_API_VERSION: String = "0.152.1+26.2"
    val PAPER_VERSION: String = "26.2.build.+"

    val MCEF_VERSION: String = "2.2.0-26.1.1"

    // https://semver.org/
    var MOD_VERSION: String = "0.1.0"

    fun createVersionString(project: Project): String {
        val builder = StringBuilder()

        val isReleaseBuild = project.hasProperty("build.release")
        val buildId = System.getenv("GITHUB_RUN_NUMBER")

        if (isReleaseBuild) {
            builder.append(MOD_VERSION)
        } else {
            builder.append(MOD_VERSION.substringBefore('-'))
            builder.append("-snapshot")
        }

        builder.append("+mc").append(MINECRAFT_VERSION)

        if (!isReleaseBuild) {
            if (buildId != null) {
                builder.append("-build.${buildId}")
            } else {
                builder.append("-local")
            }
        }

        return builder.toString()
    }
}
