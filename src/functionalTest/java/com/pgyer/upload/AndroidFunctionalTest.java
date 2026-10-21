package com.pgyer.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pgyer.upload.client.Json;
import com.pgyer.upload.testing.FakePgyerServer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Builds a small Android app with AGP and uploads its APK to the fake PGYER server. Needs an Android
 * SDK (ANDROID_HOME or -PandroidSdk); skipped without one.
 */
class AndroidFunctionalTest {

    private static final String SDK = System.getProperty("pgyer.test.androidSdk");
    private static final String BUILD_TOOLS = System.getProperty("pgyer.test.buildToolsVersion");
    private static final String KEY = "ANDROID-KEY-SECRET-1f2e";

    @TempDir
    Path dir;

    private FakePgyerServer pgyer;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(SDK != null && new File(SDK).isDirectory(), "No Android SDK; set ANDROID_HOME or -PandroidSdk");
        pgyer = new FakePgyerServer();
    }

    @AfterEach
    void tearDown() {
        if (pgyer != null) {
            pgyer.close();
        }
    }

    /**
     * AGP and Gradle versions as {@code agp:gradle} pairs: -PtestAgpVersions, else the oldest supported
     * AGP that runs on this JVM, a late AGP 8 and the current AGP 9.
     */
    static List<Arguments> versions() {
        String configured = System.getProperty("pgyer.test.agpVersions");
        List<Arguments> list = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            for (String pair : TestProject.split(configured)) {
                String[] parts = pair.split(":");
                list.add(Arguments.of(parts[0], parts.length > 1 ? parts[1] : GradleVersion.current().getVersion()));
            }
            return list;
        }
        if (Runtime.version().feature() <= 20) {
            list.add(Arguments.of("8.0.2", "8.0.2"));
        }
        list.add(Arguments.of("8.13.2", "8.14.6"));
        list.add(Arguments.of("9.4.1", GradleVersion.current().getVersion()));
        return list;
    }

    private TestProject androidProject(String agpVersion, String gradleVersion) {
        TestProject project = new TestProject(dir, gradleVersion).settings("dependencyResolutionManagement {\n"
                + "    repositories {\n"
                + "        google()\n"
                + "        mavenCentral()\n"
                + "    }\n"
                + "}\n");
        boolean assign = project.supportsKotlinAssignment();
        String set = assign ? " = " : ".set(";
        String end = assign ? "" : ")";
        project.write("local.properties", "sdk.dir=" + SDK.replace("\\", "\\\\") + "\n");
        project.write("gradle.properties", "pgyer.apiServers=" + pgyer.url() + "\n"
                + "org.gradle.jvmargs=-Xmx2g\n"
                // Never download SDK packages: that would also make the configuration cache miss.
                + "android.builder.sdkDownload=false\n"
                + aapt2Override(agpVersion));
        project.write("build.gradle.kts", "plugins {\n"
                + "    id(\"com.android.application\") version \"" + agpVersion + "\"\n"
                + "    id(\"com.pgyer.upload\") version \"" + TestProject.PLUGIN_VERSION + "\"\n"
                + "}\n"
                + "android {\n"
                + "    namespace = \"com.pgyer.sample\"\n"
                + "    compileSdk = 35\n"
                + (BUILD_TOOLS == null ? "" : "    buildToolsVersion = \"" + BUILD_TOOLS + "\"\n")
                + "    defaultConfig {\n"
                + "        applicationId = \"com.pgyer.sample\"\n"
                + "        minSdk = 21\n"
                + "        targetSdk = 35\n"
                + "        versionCode = 3\n"
                + "        versionName = \"1.0.3\"\n"
                + "    }\n"
                + "    flavorDimensions += \"tier\"\n"
                + "    productFlavors {\n"
                + "        create(\"free\") { dimension = \"tier\" }\n"
                + "        create(\"paid\") { dimension = \"tier\" }\n"
                + "    }\n"
                + "    buildTypes {\n"
                + "        getByName(\"release\") {\n"
                + "            isMinifyEnabled = false\n"
                + "            signingConfig = signingConfigs.getByName(\"debug\")\n"
                + "        }\n"
                + "    }\n"
                + "    lint { checkReleaseBuilds = false }\n"
                + "}\n"
                + "pgyer {\n"
                + "    updateDescription" + set + "\"Built by the functional test\"" + end + "\n"
                + "    oversea" + set + "true" + end + "\n"
                + "    variantFilter { it.buildType == \"release\" }\n"
                + "    variants {\n"
                + "        create(\"paidRelease\") { enabled" + set + "false" + end + " }\n"
                + "        create(\"freeRelase\") { channelShortcut" + set + "\"typo\"" + end + " }\n"
                + "        create(\"freeRelease\") {\n"
                + "            channelShortcut" + set + "\"free\"" + end + "\n"
                + "            password" + set + "\"FREE-PW-SECRET\"" + end + "\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        project.write("src/main/AndroidManifest.xml",
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                        + "    <application android:label=\"PGYER Sample\">\n"
                        + "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n"
                        + "            <intent-filter>\n"
                        + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                        + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                        + "            </intent-filter>\n"
                        + "        </activity>\n"
                        + "    </application>\n"
                        + "</manifest>\n");
        project.write("src/main/java/com/pgyer/sample/MainActivity.java", "package com.pgyer.sample;\n\n"
                + "import android.app.Activity;\n"
                + "import android.os.Bundle;\n"
                + "import android.widget.TextView;\n\n"
                + "public class MainActivity extends Activity {\n"
                + "    @Override\n"
                + "    protected void onCreate(Bundle savedInstanceState) {\n"
                + "        super.onCreate(savedInstanceState);\n"
                + "        TextView text = new TextView(this);\n"
                + "        text.setText(\"Uploaded with pgyer-gradle-plugin\");\n"
                + "        setContentView(text);\n"
                + "    }\n"
                + "}\n");
        project.env("PGYER_API_KEY", KEY);
        return project;
    }

    @ParameterizedTest(name = "AGP {0}, Gradle {1}")
    @MethodSource("versions")
    void uploadsTheVariantApkWithTheConfigurationCache(String agpVersion, String gradleVersion) {
        TestProject project = androidProject(agpVersion, gradleVersion);

        BuildResult tasks = project.build("tasks", "--group", "pgyer");
        String list = tasks.getOutput();
        assertTrue(list.contains("uploadPgyerFreeRelease - Builds the freeRelease APK and uploads it to PGYER."), list);
        assertFalse(list.contains("uploadPgyerPaidRelease"), list);
        assertFalse(list.contains("uploadPgyerFreeDebug"), list);
        assertFalse(list.contains("uploadPgyerPaidDebug"), list);
        assertTrue(list.contains("[PGYER] pgyer.variants has settings for \"freeRelase\", but this project has no"
                + " such variant. Variants: freeDebug, paidDebug, freeRelease, paidRelease"), list);
        assertFalse(list.contains("settings for \"freeRelease\""), list);

        BuildResult first = project.build("uploadPgyerFreeRelease", "--configuration-cache");

        assertEquals(TaskOutcome.SUCCESS, first.task(":uploadPgyerFreeRelease").getOutcome());
        assertNotNull(first.task(":packageFreeRelease"), "The APK is built first");
        Map<String, String> form = pgyer.tokenRequests.get(0).form;
        assertEquals(KEY, form.get("_api_key"));
        assertEquals("android", form.get("buildType"));
        assertEquals("Built by the functional test", form.get("buildUpdateDescription"));
        assertEquals("free", form.get("buildChannelShortcut"));
        assertEquals("2", form.get("buildInstallType"));
        assertEquals("1", form.get("oversea"));
        assertEquals("sample-free-release.apk", pgyer.uploads.get(0).fields.get("x-cos-meta-file-name"));
        byte[] apk = pgyer.uploads.get(0).fileContent;
        assertTrue(apk.length > 1000 && apk[0] == 'P' && apk[1] == 'K', "uploads a zip (APK)");
        String output = first.getOutput();
        assertTrue(output.contains("[PGYER] Download page: https://www.pgyer.com/myapp"), output);
        assertTrue(output.contains("[PGYER] QR code: https://www.pgyer.com/app/qrcodeHistory/abc123"), output);
        assertFalse(output.contains(KEY) || output.contains("FREE-PW-SECRET") || output.contains(FakePgyerServer.SIGNATURE));

        Map<?, ?> json = (Map<?, ?>) parse(project.read("build/outputs/pgyer/freeRelease.json"));
        assertEquals("https://www.pgyer.com/myapp", json.get("appPgyerURL"));

        BuildResult second = project.build("uploadPgyerFreeRelease", "--configuration-cache");

        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertEquals(TaskOutcome.SUCCESS, second.task(":uploadPgyerFreeRelease").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":packageFreeRelease").getOutcome());
        assertEquals(2, pgyer.uploads.size());
    }

    /**
     * The aapt2 of AGP 8.0 and 8.1 cannot read the resource table of android-35's android.jar, so
     * those versions use the SDK's own aapt2 when the SDK has the build tools the test asks for.
     */
    private static String aapt2Override(String agpVersion) {
        if (BUILD_TOOLS == null || !(agpVersion.startsWith("8.0.") || agpVersion.startsWith("8.1."))) {
            return "";
        }
        File aapt2 = new File(SDK, "build-tools/" + BUILD_TOOLS + "/aapt2");
        return aapt2.isFile() ? "android.aapt2FromMavenOverride=" + aapt2.getAbsolutePath().replace("\\", "/") + "\n" : "";
    }

    private static Object parse(String json) {
        try {
            return Json.parse(json);
        } catch (Json.ParseException e) {
            throw new AssertionError(e);
        }
    }
}
