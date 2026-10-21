package com.pgyer.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pgyer.upload.client.Json;
import com.pgyer.upload.testing.FakePgyerServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PlainTaskFunctionalTest {

    private static final String KEY = "TEST-KEY-SECRET-5a6b";
    private static final String PASSWORD = "TEST-PW-SECRET-7c8d";

    @TempDir
    Path dir;

    private FakePgyerServer pgyer;

    @BeforeEach
    void setUp() throws IOException {
        pgyer = new FakePgyerServer();
    }

    @AfterEach
    void tearDown() {
        pgyer.close();
    }

    static List<String> gradleVersions() {
        return TestProject.gradleVersions();
    }

    private TestProject project(String gradleVersion) throws IOException {
        TestProject project = new TestProject(dir, gradleVersion).settings("");
        project.write("gradle.properties", "pgyer.apiServers=" + pgyer.url() + "\n");
        Files.writeString(dir.resolve("MyApp.ipa"), "IPA-CONTENT");
        return project;
    }

    /** Kotlin DSL that works on every supported Gradle version (no `=` for lazy properties before 8.2). */
    private static String kotlinBuild(boolean assign) {
        String set = assign ? " = " : ".set(";
        String end = assign ? "" : ")";
        return "plugins {\n"
                + "    id(\"com.pgyer.upload\") version \"" + TestProject.PLUGIN_VERSION + "\"\n"
                + "}\n"
                + "pgyer {\n"
                + "    updateDescription" + set + "\"From the extension\"" + end + "\n"
                + "    description" + set + "\"Demo app\"" + end + "\n"
                + "    password" + set + "\"" + PASSWORD + "\"" + end + "\n"
                + "    installStartDate" + set + "\"2026-11-01\"" + end + "\n"
                + "    installEndDate" + set + "\"2026-11-30\"" + end + "\n"
                + "    channelShortcut" + set + "\"beta\"" + end + "\n"
                + "    oversea" + set + "false" + end + "\n"
                + "    downloadFileName" + set + "\"MyApp-1.2.0.ipa\"" + end + "\n"
                + "}\n"
                + "tasks.register<com.pgyer.upload.PgyerUploadTask>(\"uploadIpa\") {\n"
                + "    file" + set + "layout.projectDirectory.file(\"MyApp.ipa\")" + end + "\n"
                + "    updateDescription" + set + "\"From the task\"" + end + "\n"
                + "}\n";
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void uploadsAFileWithThePlainTask(String gradleVersion) throws IOException {
        TestProject project = project(gradleVersion);
        project.write("build.gradle.kts", kotlinBuild(project.supportsKotlinAssignment()));
        project.env("PGYER_API_KEY", KEY);

        BuildResult result = project.build("uploadIpa", "--info");

        assertEquals(TaskOutcome.SUCCESS, result.task(":uploadIpa").getOutcome());
        Map<String, String> form = pgyer.tokenRequests.get(0).form;
        assertEquals(KEY, form.get("_api_key"));
        assertEquals("ios", form.get("buildType"));
        assertEquals("From the task", form.get("buildUpdateDescription"));
        assertEquals("Demo app", form.get("buildDescription"));
        assertEquals("2", form.get("buildInstallType"));
        assertEquals(PASSWORD, form.get("buildPassword"));
        assertEquals("1", form.get("buildInstallDate"));
        assertEquals("2026-11-01", form.get("buildInstallStartDate"));
        assertEquals("2026-11-30", form.get("buildInstallEndDate"));
        assertEquals("beta", form.get("buildChannelShortcut"));
        assertEquals("2", form.get("oversea"));
        assertEquals("MyApp-1.2.0.ipa", pgyer.uploads.get(0).fields.get("x-cos-meta-file-name"));
        assertEquals("IPA-CONTENT", new String(pgyer.uploads.get(0).fileContent));

        String userAgent = pgyer.tokenRequests.get(0).headers.get("user-agent");
        String expected = "pgyer-gradle-plugin/" + TestProject.PLUGIN_VERSION + " Gradle/" + gradleVersion + " Java/";
        assertTrue(userAgent.startsWith(expected), userAgent);

        String output = result.getOutput();
        assertTrue(output.contains("[PGYER] Published My App 1.2.0 (build 7)"), output);
        assertTrue(output.contains("[PGYER] Download page: https://www.pgyer.com/myapp"), output);
        assertTrue(output.contains("[PGYER] QR code: https://www.pgyer.com/app/qrcodeHistory/abc123"), output);
        assertNoSecrets(output);

        Map<?, ?> json = (Map<?, ?>) parse(project.read("build/outputs/pgyer/uploadIpa.json"));
        assertEquals("abc123", json.get("buildKey"));
        assertEquals("1.2.0", json.get("buildVersion"));
        assertEquals("https://www.pgyer.com/myapp", json.get("appPgyerURL"));
        assertEquals("https://www.pgyer.com/abc123", json.get("appBuildURL"));
        assertEquals("https://www.pgyer.com/app/qrcodeHistory/abc123", json.get("appQRCodeURL"));
        assertNull(json.get("buildDescription"));
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void runsEveryTimeAndReusesTheConfigurationCache(String gradleVersion) throws IOException {
        TestProject project = project(gradleVersion);
        project.write("build.gradle.kts", kotlinBuild(project.supportsKotlinAssignment()));

        project.env("PGYER_API_KEY", "first-key");
        BuildResult first = project.build("uploadIpa", "--configuration-cache");
        project.env("PGYER_API_KEY", "second-key");
        BuildResult second = project.build("uploadIpa", "--configuration-cache");

        assertEquals(TaskOutcome.SUCCESS, first.task(":uploadIpa").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, second.task(":uploadIpa").getOutcome());
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertEquals(2, pgyer.uploads.size());
        // The key is read when the task runs, not stored in the configuration cache.
        assertEquals("first-key", pgyer.tokenRequests.get(0).form.get("_api_key"));
        assertEquals("second-key", pgyer.tokenRequests.get(1).form.get("_api_key"));
        assertNoSecrets(first.getOutput() + second.getOutput());
    }

    @Test
    void groovyDsl() throws IOException {
        TestProject project = project(GradleVersion.current().getVersion());
        project.write("build.gradle", "plugins {\n"
                + "    id 'com.pgyer.upload' version '" + TestProject.PLUGIN_VERSION + "'\n"
                + "}\n"
                + "pgyer {\n"
                + "    apiKey = 'groovy-key'\n"
                + "    installType = 'invite'\n"
                + "    oversea = true\n"
                + "    processingTimeout = 120\n"
                + "    variants {\n"
                + "        freeRelease { channelShortcut = 'free-beta' }\n"
                + "        paidRelease { enabled = false }\n"
                + "    }\n"
                + "}\n"
                + "println \"variants: ${pgyer.variants.collect { it.name + '=' + it.channelShortcut.getOrElse('-') + '/'"
                + " + it.enabled.getOrElse(true) }}\"\n"
                + "tasks.register('uploadIpa', com.pgyer.upload.PgyerUploadTask) {\n"
                + "    file = layout.projectDirectory.file('MyApp.ipa')\n"
                + "    updateDescription = 'Groovy'\n"
                + "}\n");

        BuildResult result = project.build("uploadIpa");

        assertEquals(TaskOutcome.SUCCESS, result.task(":uploadIpa").getOutcome());
        Map<String, String> form = pgyer.tokenRequests.get(0).form;
        assertEquals("groovy-key", form.get("_api_key"));
        assertEquals("3", form.get("buildInstallType"));
        assertEquals("1", form.get("oversea"));
        assertEquals("Groovy", form.get("buildUpdateDescription"));
        assertFalse(form.containsKey("buildPassword"));
        assertEquals("MyApp.ipa", pgyer.uploads.get(0).fields.get("x-cos-meta-file-name"));
        assertTrue(result.getOutput().contains("variants: [freeRelease=free-beta/true, paidRelease=-/false]"),
                result.getOutput());
    }

    @Test
    void readsTheKeyFromTheGradlePropertyFirst() throws IOException {
        TestProject project = project(GradleVersion.current().getVersion());
        project.write("build.gradle.kts", kotlinBuild(true));
        project.env("PGYER_API_KEY", "env-key");

        project.build("uploadIpa", "-Ppgyer.apiKey=property-key");

        assertEquals("property-key", pgyer.tokenRequests.get(0).form.get("_api_key"));
    }

    @Test
    void sendsOnlyWhatIsConfigured() throws IOException {
        TestProject project = project(GradleVersion.current().getVersion());
        project.write("build.gradle.kts", "plugins { id(\"com.pgyer.upload\") version \""
                + TestProject.PLUGIN_VERSION + "\" }\n"
                + "tasks.register<com.pgyer.upload.PgyerUploadTask>(\"uploadIpa\") {\n"
                + "    file = layout.projectDirectory.file(\"MyApp.ipa\")\n"
                + "}\n");
        project.env("PGYER_API_KEY", KEY);

        project.build("uploadIpa");

        assertEquals(Map.of("_api_key", KEY, "buildType", "ios"), pgyer.tokenRequests.get(0).form);
    }

    @Test
    void explainsAMissingApiKey() throws IOException {
        TestProject project = project(GradleVersion.current().getVersion());
        project.write("build.gradle.kts", kotlinBuild(true));

        BuildResult result = project.fail("uploadIpa");

        assertTrue(result.getOutput().contains("No PGYER API key. Set the PGYER_API_KEY environment variable, the"
                + " pgyer.apiKey Gradle property"), result.getOutput());
        assertTrue(pgyer.tokenRequests.isEmpty());
    }

    @Test
    void rejectsUnknownFileTypes() throws IOException {
        TestProject project = project(GradleVersion.current().getVersion());
        Files.writeString(dir.resolve("app.aab"), "AAB");
        project.write("build.gradle.kts", "plugins { id(\"com.pgyer.upload\") version \""
                + TestProject.PLUGIN_VERSION + "\" }\n"
                + "tasks.register<com.pgyer.upload.PgyerUploadTask>(\"uploadAab\") {\n"
                + "    file = layout.projectDirectory.file(\"app.aab\")\n"
                + "}\n");
        project.env("PGYER_API_KEY", KEY);

        BuildResult result = project.fail("uploadAab");

        assertTrue(result.getOutput().contains("Cannot tell the app type of app.aab. PGYER accepts .apk, .ipa and .hap"
                + " files."), result.getOutput());
        assertTrue(pgyer.tokenRequests.isEmpty());
    }

    @Test
    void rejectsAnInvalidInstallType() throws IOException {
        TestProject project = project(GradleVersion.current().getVersion());
        project.write("build.gradle.kts", kotlinBuild(true) + "pgyer { installType = \"private\" }\n");
        project.env("PGYER_API_KEY", KEY);

        BuildResult result = project.fail("uploadIpa");

        assertTrue(result.getOutput().contains("installType must be 1 (public), 2 (password) or 3 (invite), not"
                + " \"private\"."), result.getOutput());
    }

    @Test
    void reportsApiErrorsWithAHint() throws IOException {
        pgyer.tokenError = 1001;
        TestProject project = project(GradleVersion.current().getVersion());
        project.write("build.gradle.kts", kotlinBuild(true));
        project.env("PGYER_API_KEY", KEY);

        BuildResult result = project.fail("uploadIpa", "--info");

        String output = result.getOutput();
        assertTrue(output.contains("Uploading to PGYER failed. PGYER API error 1001: error from fake server"), output);
        assertTrue(output.contains("Check that the API key is correct. You can find it at"
                + " https://www.pgyer.com/account/api"), output);
        assertNoSecrets(output);
    }

    @Test
    void theApiKeyAndPasswordAreNotTaskInputs() throws IOException {
        TestProject project = project(GradleVersion.current().getVersion());
        project.write("build.gradle.kts", kotlinBuild(true));
        project.env("PGYER_API_KEY", KEY);

        BuildResult result = project.build("uploadIpa", "--debug");

        assertNoSecrets(result.getOutput());
    }

    @Test
    void noVariantTasksWithoutAndroid() throws IOException {
        TestProject project = project(GradleVersion.current().getVersion());
        project.write("build.gradle.kts", kotlinBuild(true));

        BuildResult result = project.build("tasks", "--all");

        assertTrue(result.getOutput().contains("uploadIpa"), result.getOutput());
        assertFalse(result.getOutput().contains("uploadPgyer"), result.getOutput());
    }

    private static void assertNoSecrets(String output) {
        for (String secret : List.of(KEY, PASSWORD, "first-key", "second-key", FakePgyerServer.SIGNATURE,
                FakePgyerServer.COS_TOKEN)) {
            assertFalse(output.contains(secret), "The output contains " + secret);
        }
    }

    private static Object parse(String json) {
        try {
            return Json.parse(json);
        } catch (Json.ParseException e) {
            throw new AssertionError(e);
        }
    }
}
