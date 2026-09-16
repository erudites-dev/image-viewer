import org.gradle.api.Project

object BuildConfig {
    val JAVA_VERSION: Int = 21

    val MINECRAFT_VERSION_RANGE: String = ">=1.21.5 <1.21.6" // range: ">=26.1 <27.1"
    val MINECRAFT_PUBLISH_END_OVERRIDE: String? = "1.21.5"
    val MINECRAFT_VERSION: String = "1.21.5"
    val NEOFORGE_VERSION: String = "21.5.98"
    val FABRIC_LOADER_VERSION: String = "0.19.5"
    val FABRIC_API_VERSION: String = "0.128.2+1.21.5"
    val PAPER_VERSION: String = "1.21.5-R0.1-SNAPSHOT"

    // https://semver.org/
    var MOD_VERSION: String = "0.2.0"

    val MINECRAFT_VERSION_MIN: String
        get() = LOWER_BOUND.version

    val MINECRAFT_VERSION_MAVEN: String
        get() = buildString {
            append(if (LOWER_BOUND.operator == ">=") '[' else '(')
            append(LOWER_BOUND.version)
            append(',')
            UPPER_BOUND?.let { append(it.version) }
            append(if (UPPER_BOUND?.operator == "<=") ']' else ')')
        }

    val MINECRAFT_PUBLISH_END: String
        get() = MINECRAFT_PUBLISH_END_OVERRIDE
            ?: UPPER_BOUND?.takeIf { it.operator == "<=" }?.version
            ?: "latest"

    private data class Bound(val operator: String, val version: String)

    private val BOUNDS: List<Bound> = MINECRAFT_VERSION_RANGE
        .split(" ")
        .filter { it.isNotBlank() }
        .map { predicate ->
            val operator = predicate.takeWhile { !it.isDigit() }
            Bound(operator, predicate.removePrefix(operator))
        }

    private val LOWER_BOUND: Bound = BOUNDS.first { it.operator.startsWith(">") }
    private val UPPER_BOUND: Bound? = BOUNDS.firstOrNull { it.operator.startsWith("<") }

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
