package com.pgyer.upload;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No Android SDK needed: the failure happens when the plugins are applied. */
class AndroidClassLoaderFunctionalTest {

    @TempDir
    Path dir;

    @Test
    void explainsHowToDeclareThePluginWhenItCannotSeeAgp() {
        TestProject project = new TestProject(dir, GradleVersion.current().getVersion())
                .settings("include(\"app\")\n")
                // The root project's class loader is the parent of app's, so it cannot see AGP.
                .write("build.gradle.kts", "plugins {\n"
                        + "    id(\"com.pgyer.upload\") version \"" + TestProject.PLUGIN_VERSION + "\" apply false\n"
                        + "}\n")
                .write("app/build.gradle.kts", "plugins {\n"
                        + "    id(\"com.android.application\") version \"9.4.1\"\n"
                        + "    id(\"com.pgyer.upload\")\n"
                        + "}\n");

        BuildResult result = project.fail("help");

        assertTrue(result.getOutput().contains("The com.pgyer.upload plugin cannot see the classes of the Android"
                + " Gradle Plugin"), result.getOutput());
        assertTrue(result.getOutput().contains("Declare com.pgyer.upload in the same build script as"
                + " com.android.application"), result.getOutput());
    }
}
