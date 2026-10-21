package com.pgyer.upload;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.util.GradleVersion;

/**
 * A throwaway Gradle build that resolves the plugin from the functional test repository, the way a
 * real build resolves it from the Gradle Plugin Portal.
 */
final class TestProject {

    static final String PLUGIN_VERSION = System.getProperty("pgyer.test.pluginVersion");
    static final String REPO = System.getProperty("pgyer.test.repo");

    final Path dir;
    private final String gradleVersion;
    private final Map<String, String> environment = new HashMap<>(System.getenv());

    TestProject(Path dir, String gradleVersion) {
        this.dir = dir;
        this.gradleVersion = gradleVersion;
        environment.remove("PGYER_API_KEY");
        // Keep AGP's analytics settings and debug keystore out of the real home directory.
        environment.put("ANDROID_USER_HOME", dir.resolve(".android-user-home").toString());
    }

    /** Gradle versions to test with: -PtestGradleVersions, else the current one and the oldest that runs on this JVM. */
    static List<String> gradleVersions() {
        String configured = System.getProperty("pgyer.test.gradleVersions");
        if (configured != null && !configured.isBlank()) {
            return split(configured);
        }
        List<String> versions = new ArrayList<>();
        versions.add(GradleVersion.current().getVersion());
        int java = Runtime.version().feature();
        if (java <= 20) {
            versions.add("8.0.2");
        } else if (java <= 22) {
            versions.add("8.5");
        }
        return versions;
    }

    static List<String> split(String value) {
        List<String> list = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                list.add(part.trim());
            }
        }
        return list;
    }

    /** Whether Kotlin DSL scripts can assign lazy properties with {@code =} (Gradle 8.2+). */
    boolean supportsKotlinAssignment() {
        return GradleVersion.version(gradleVersion).compareTo(GradleVersion.version("8.2")) >= 0;
    }

    TestProject settings(String extra) {
        String repo = REPO.replace("\\", "/");
        return write("settings.gradle.kts", "pluginManagement {\n"
                + "    repositories {\n"
                + "        maven { url = uri(\"" + repo + "\") }\n"
                + "        google()\n"
                + "        mavenCentral()\n"
                + "        gradlePluginPortal()\n"
                + "    }\n"
                + "}\n"
                + "rootProject.name = \"sample\"\n"
                + extra);
    }

    TestProject write(String path, String content) {
        try {
            Path file = dir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String read(String path) {
        try {
            return Files.readString(dir.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    File file(String path) {
        return dir.resolve(path).toFile();
    }

    TestProject env(String name, String value) {
        if (value == null) {
            environment.remove(name);
        } else {
            environment.put(name, value);
        }
        return this;
    }

    private GradleRunner runner(String... args) {
        List<String> arguments = new ArrayList<>(Arrays.asList(args));
        arguments.add("--stacktrace");
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withGradleVersion(gradleVersion)
                .withEnvironment(environment)
                .withArguments(arguments);
    }

    BuildResult build(String... args) {
        return runner(args).build();
    }

    BuildResult fail(String... args) {
        return runner(args).buildAndFail();
    }
}
