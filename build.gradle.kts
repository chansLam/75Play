// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

val shellThemeSources = fileTree("common/src/main/java/com/shihab/diplay/ui/theme") {
    include("**/*.kt")
}

val detektDesign by tasks.registering {
    group = "verification"
    description = "Verify the API 19 shell design tokens and banned UI patterns."
    inputs.files(shellThemeSources, file("DESIGN_RULES.md"), file("config/detekt/detekt.yml"))
    doLast {
        val violations = mutableListOf<String>()
        val banned = linkedMapOf(
            "generic gradient" to Regex("(?i)(linearGradient|radialGradient|sweepGradient)"),
            "filler copy" to Regex("(?i)(Lorem ipsum|Lightning fast|Powered by AI|Get started in seconds|Beautiful and intuitive|Seamlessly integrate|Effortlessly|Reimagined)"),
            "decorative animation" to Regex("(?i)(infiniteRepeatable|rememberInfiniteTransition|parallax|autoadvance)"),
            "banned buzzword" to Regex("(?i)\\b(clean|modern|professional|sleek|premium|beautiful|elegant)\\b"),
        )
        inputs.files.files.filter { it.isFile && it.extension == "kt" }.forEach { source ->
            val text = source.readText()
            banned.forEach { (label, pattern) ->
                pattern.findAll(text).forEach { match ->
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    violations += "${source.invariantSeparatorsPath}:$line [$label] ${match.value}"
                }
            }
            if (source.name != "Color.kt") {
                Regex("0x[0-9A-Fa-f]{6,8}").findAll(text).forEach { match ->
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    violations += "${source.invariantSeparatorsPath}:$line [color outside Color.kt] ${match.value}"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Shell design rule violations:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.register("detekt") {
    group = "verification"
    dependsOn(detektDesign)
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("detektDesign"))
    }
    tasks.withType<Test>().configureEach {
        maxParallelForks = 1
        jvmArgs("-Xmx512m", "-XX:+UseSerialGC")
    }
}
