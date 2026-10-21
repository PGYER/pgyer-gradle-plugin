import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "com.pgyer"
description = "Upload Android, iOS and HarmonyOS builds to PGYER (www.pgyer.com) from Gradle."

repositories {
    google()
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    // AGP 8 runs on Java 17, so the plugin must too. Any JDK 17+ can build it.
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation,unchecked,rawtypes")
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

dependencies {
    // Compiled against the oldest supported Android Gradle Plugin API; the build that applies the
    // plugin provides AGP at runtime.
    compileOnly("com.android.tools.build:gradle-api:8.0.2")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.7.10")
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("com/pgyer/upload/plugin.properties") {
        expand("version" to version)
    }
}

gradlePlugin {
    website = "https://github.com/PGYER/pgyer-gradle-plugin"
    vcsUrl = "https://github.com/PGYER/pgyer-gradle-plugin.git"
    plugins {
        create("pgyerUpload") {
            id = "com.pgyer.upload"
            implementationClass = "com.pgyer.upload.PgyerUploadPlugin"
            displayName = "Upload to PGYER"
            description = "Uploads Android APKs (one task per AGP variant) and any .apk, .ipa or .hap file " +
                "to PGYER (www.pgyer.com, 蒲公英) for beta distribution, then reports the download page and QR code."
            tags = listOf("pgyer", "android", "apk", "ipa", "harmonyos", "upload", "distribution", "beta-testing")
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

val functionalTestRepo = layout.buildDirectory.dir("functional-test-repo")

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "Upload to PGYER Gradle plugin"
            description = project.description
            url = "https://github.com/PGYER/pgyer-gradle-plugin"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/license/mit"
                }
            }
            organization {
                name = "PGYER"
                url = "https://www.pgyer.com"
            }
            scm {
                url = "https://github.com/PGYER/pgyer-gradle-plugin"
                connection = "scm:git:https://github.com/PGYER/pgyer-gradle-plugin.git"
            }
        }
    }
    repositories {
        // The functional tests resolve the plugin from here, like a build would from the Plugin Portal.
        maven {
            name = "functionalTest"
            url = uri(functionalTestRepo)
        }
    }
}

val junitVersion = "5.13.4"

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(junitVersion)
            sources.java.srcDir("src/testSupport/java")
        }

        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter(junitVersion)
            sources.java.srcDir("src/testSupport/java")
            dependencies {
                implementation(project())
                implementation(gradleTestKit())
            }
            targets.configureEach {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                    dependsOn("publishAllPublicationsToFunctionalTestRepository")
                    val repo = functionalTestRepo.get().asFile.absolutePath
                    val pluginVersion = project.version.toString()
                    systemProperty("pgyer.test.repo", repo)
                    systemProperty("pgyer.test.pluginVersion", pluginVersion)
                    // Optional: -PtestGradleVersions=8.14.3,9.8.1 and -PtestAgpVersions=8.7.3,9.4.1
                    providers.gradleProperty("testGradleVersions").orNull?.let { systemProperty("pgyer.test.gradleVersions", it) }
                    providers.gradleProperty("testAgpVersions").orNull?.let { systemProperty("pgyer.test.agpVersions", it) }
                    providers.gradleProperty("testBuildToolsVersion").orNull?.let { systemProperty("pgyer.test.buildToolsVersion", it) }
                    val sdk = providers.gradleProperty("androidSdk")
                        .orElse(providers.environmentVariable("ANDROID_HOME"))
                        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
                        .orNull
                    sdk?.let { systemProperty("pgyer.test.androidSdk", it) }
                    // Keep a developer's real API key out of the test builds.
                    environment.remove("PGYER_API_KEY")
                    maxParallelForks = 1
                    testLogging {
                        events("passed", "skipped", "failed")
                        showStandardStreams = providers.gradleProperty("showTestOutput").isPresent
                    }
                }
            }
        }
    }
}

tasks.named<Test>("test") {
    environment.remove("PGYER_API_KEY")
    testLogging { events("passed", "skipped", "failed") }
}

tasks.check {
    dependsOn(testing.suites.named("functionalTest"))
}

tasks.validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
}
