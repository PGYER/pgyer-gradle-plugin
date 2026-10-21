package com.pgyer.upload.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pgyer.upload.testing.FakePgyerServer;
import com.pgyer.upload.testing.FakePgyerServer.Request;
import com.pgyer.upload.testing.FakePgyerServer.Upload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PgyerClientTest {

    private static final String API_KEY = "API-KEY-SECRET-1b2c";
    private static final String PASSWORD = "INSTALL-PW-SECRET-3d4e";
    private static final String USER_AGENT = "pgyer-gradle-plugin/1.0.0 Gradle/9.8.1 Java/21";

    @TempDir
    Path dir;

    private FakePgyerServer pgyer;
    private Path apk;
    private final List<String> lifecycle = new ArrayList<>();
    private final List<String> info = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        pgyer = new FakePgyerServer();
        apk = Files.writeString(dir.resolve("app-release.apk"), "APK-CONTENT");
    }

    @AfterEach
    void tearDown() {
        pgyer.close();
    }

    private PgyerClient client(String... servers) {
        PgyerClient client = new PgyerClient(List.of(servers), USER_AGENT, new PgyerLog() {
            @Override
            public void lifecycle(String message) {
                lifecycle.add(message);
            }

            @Override
            public void info(String message) {
                info.add(message);
            }

            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        });
        client.setPollInterval(Duration.ofMillis(10));
        return client;
    }

    private static UploadOptions options() {
        return new UploadOptions().setApiKey(API_KEY).setBuildType("android");
    }

    private String allLogs() {
        List<String> all = new ArrayList<>(lifecycle);
        all.addAll(info);
        all.addAll(warnings);
        return String.join("\n", all);
    }

    @Test
    void uploadsTheFileAndWaitsUntilTheBuildIsPublished() throws Exception {
        pgyer.processingPolls = 2;
        UploadOptions options = options()
                .setInstallType("password")
                .setPassword(PASSWORD)
                .setDescription("Demo app")
                .setUpdateDescription("Fixed the login crash")
                .setInstallPeriod("2026-11-01", "2026-11-30")
                .setChannelShortcut("beta")
                .setOversea(false);

        UploadResult result = client(pgyer.url()).upload(apk, options);

        assertEquals(FakePgyerServer.BUILD_KEY, result.getBuildKey());
        assertEquals("https://www.pgyer.com/" + FakePgyerServer.SHORTCUT, result.getDownloadPageUrl());
        assertEquals("https://www.pgyer.com/" + FakePgyerServer.BUILD_KEY, result.getBuildPageUrl());
        assertEquals("https://www.pgyer.com/app/qrcodeHistory/abc123", result.getQrCodeUrl());
        assertEquals("My App 1.2.0 (build 7)", result.describe());
        assertNull(result.getBuildInfo().get("buildDescription"));

        Map<String, String> form = pgyer.tokenRequests.get(0).form;
        assertEquals(API_KEY, form.get("_api_key"));
        assertEquals("android", form.get("buildType"));
        assertEquals("2", form.get("buildInstallType"));
        assertEquals(PASSWORD, form.get("buildPassword"));
        assertEquals("Demo app", form.get("buildDescription"));
        assertEquals("Fixed the login crash", form.get("buildUpdateDescription"));
        assertEquals("1", form.get("buildInstallDate"));
        assertEquals("2026-11-01", form.get("buildInstallStartDate"));
        assertEquals("2026-11-30", form.get("buildInstallEndDate"));
        assertEquals("beta", form.get("buildChannelShortcut"));
        assertEquals("2", form.get("oversea"));

        // Polled until published, with the build key from the token.
        assertEquals(3, pgyer.buildInfoRequests.size());
        for (Request poll : pgyer.buildInfoRequests) {
            assertEquals(Map.of("_api_key", API_KEY, "buildKey", FakePgyerServer.BUILD_KEY + ".apk"), poll.form);
        }
        assertTrue(lifecycle.contains("[PGYER] Upload finished. Waiting for PGYER to process the build..."));
    }

    @Test
    void sendsOnlyTheSettingsThatAreSet() throws Exception {
        client(pgyer.url()).upload(apk, options());

        Map<String, String> form = pgyer.tokenRequests.get(0).form;
        assertEquals(Set.of("_api_key", "buildType"), form.keySet());
    }

    @Test
    void blankSettingsAreNotSent() throws Exception {
        UploadOptions options = options()
                .setInstallType(" ")
                .setPassword("   ")
                .setDescription("")
                .setUpdateDescription("  ")
                .setChannelShortcut("")
                .setInstallPeriod("", " ");

        client(pgyer.url()).upload(apk, options);

        assertEquals(Set.of("_api_key", "buildType"), pgyer.tokenRequests.get(0).form.keySet());
    }

    @Test
    void aPasswordOnItsOwnMeansPasswordInstall() throws Exception {
        client(pgyer.url()).upload(apk, options().setPassword(PASSWORD));

        Map<String, String> form = pgyer.tokenRequests.get(0).form;
        assertEquals("2", form.get("buildInstallType"));
        assertEquals(PASSWORD, form.get("buildPassword"));
    }

    @Test
    void anExplicitInstallTypeIsKept() throws Exception {
        client(pgyer.url()).upload(apk, options().setInstallType("invite").setPassword(PASSWORD));

        assertEquals("3", pgyer.tokenRequests.get(0).form.get("buildInstallType"));
    }

    @Test
    void installTypeNamesAndNumbers() {
        assertEquals("1", UploadOptions.parseInstallType("1"));
        assertEquals("1", UploadOptions.parseInstallType("Public"));
        assertEquals("2", UploadOptions.parseInstallType(" 2 "));
        assertEquals("2", UploadOptions.parseInstallType("PASSWORD"));
        assertEquals("3", UploadOptions.parseInstallType("3"));
        assertEquals("3", UploadOptions.parseInstallType("invite"));
        assertEquals("3", UploadOptions.parseInstallType("invitation"));
        assertNull(UploadOptions.parseInstallType(null));
        assertNull(UploadOptions.parseInstallType(""));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> UploadOptions.parseInstallType("4"));
        assertTrue(e.getMessage().contains("installType must be 1 (public), 2 (password) or 3 (invite)"));
    }

    @Test
    void overseaTrueIsOne() throws Exception {
        client(pgyer.url()).upload(apk, options().setOversea(true));

        assertEquals("1", pgyer.tokenRequests.get(0).form.get("oversea"));
    }

    @Test
    void overseaFalseIsTwo() throws Exception {
        client(pgyer.url()).upload(apk, options().setOversea(false));

        assertEquals("2", pgyer.tokenRequests.get(0).form.get("oversea"));
    }

    @Test
    void overseaUnsetIsNotSent() throws Exception {
        client(pgyer.url()).upload(apk, options().setOversea(null));

        assertFalse(pgyer.tokenRequests.get(0).form.containsKey("oversea"));
    }

    @Test
    void buildTypeComesFromTheExtension() {
        assertEquals("android", UploadOptions.buildTypeOf("app-release.apk"));
        assertEquals("android", UploadOptions.buildTypeOf("APP.APK"));
        assertEquals("ios", UploadOptions.buildTypeOf("MyApp.ipa"));
        assertEquals("harmony", UploadOptions.buildTypeOf("entry-default-signed.hap"));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> UploadOptions.buildTypeOf("app.aab"));
        assertEquals("Cannot tell the app type of app.aab. PGYER accepts .apk, .ipa and .hap files.", e.getMessage());
    }

    @Test
    void installPeriodNeedsBothDates() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> client(pgyer.url()).upload(apk, options().setInstallPeriod("2026-11-01", null)));
        assertEquals("Set both installStartDate and installEndDate, or neither.", e.getMessage());
        assertTrue(pgyer.tokenRequests.isEmpty());
    }

    @Test
    void installPeriodDatesAreChecked() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> client(pgyer.url()).upload(apk, options().setInstallPeriod("2026-11-01", "30.11.2026")));
        assertTrue(e.getMessage().startsWith("installEndDate must be a date like 2026-12-31"), e.getMessage());

        e = assertThrows(IllegalArgumentException.class,
                () -> client(pgyer.url()).upload(apk, options().setInstallPeriod("2026-11-30", "2026-11-01")));
        assertTrue(e.getMessage().contains("is before installStartDate"), e.getMessage());
        assertTrue(pgyer.tokenRequests.isEmpty());
    }

    @Test
    void sendsTheUserAgentAndKeepsTheKeyOutOfUrlsAndHeaders() throws Exception {
        client(pgyer.url()).upload(apk, options());

        List<Request> apiRequests = new ArrayList<>(pgyer.tokenRequests);
        apiRequests.addAll(pgyer.buildInfoRequests);
        for (Request request : apiRequests) {
            assertEquals("POST", request.method);
            assertEquals(USER_AGENT, request.headers.get("user-agent"));
            assertEquals("application/x-www-form-urlencoded", request.headers.get("content-type"));
            assertFalse(request.uri.contains("?"), request.uri);
            assertFalse(request.uri.contains(API_KEY), request.uri);
            assertFalse(request.headers.toString().contains(API_KEY));
        }
        assertEquals(USER_AGENT, pgyer.uploads.get(0).headers.get("user-agent"));
    }

    @Test
    void uploadsToStorageWithEveryTokenParameterAndTheFileName() throws Exception {
        client(pgyer.url()).upload(apk, options());

        Upload upload = pgyer.uploads.get(0);
        assertEquals(FakePgyerServer.BUILD_KEY + ".apk", upload.fields.get("key"));
        assertEquals(FakePgyerServer.SIGNATURE, upload.fields.get("signature"));
        assertEquals(FakePgyerServer.COS_TOKEN, upload.fields.get("x-cos-security-token"));
        assertEquals("app-release.apk", upload.fields.get("x-cos-meta-file-name"));
        assertEquals("app-release.apk", upload.fileName);
        assertArrayEquals("APK-CONTENT".getBytes(StandardCharsets.UTF_8), upload.fileContent);
        assertTrue(upload.headers.get("content-type").startsWith("multipart/form-data; boundary="));
        // A fixed length, not chunked: COS needs Content-Length.
        assertEquals(String.valueOf(upload.bodyLength), upload.headers.get("content-length"));
        assertNull(upload.headers.get("transfer-encoding"));
    }

    @Test
    void streamsLargeFilesIntact() throws Exception {
        byte[] content = new byte[5 * 1024 * 1024 + 123];
        new Random(42).nextBytes(content);
        Path big = Files.write(dir.resolve("big.apk"), content);

        client(pgyer.url()).upload(big, options());

        assertArrayEquals(content, pgyer.uploads.get(0).fileContent);
        assertTrue(lifecycle.contains("[PGYER] Uploading big.apk (5.0 MB)..."), lifecycle.toString());
        assertTrue(lifecycle.stream().anyMatch(l -> l.startsWith("[PGYER] Uploaded 50% (")), lifecycle.toString());
        assertTrue(lifecycle.contains("[PGYER] Uploaded 100% (5.0 MB)"), lifecycle.toString());
    }

    @Test
    void usesTheDownloadFileName() throws Exception {
        client(pgyer.url()).upload(apk, options().setDownloadFileName("MyApp-1.2.0.apk"));

        assertEquals("MyApp-1.2.0.apk", pgyer.uploads.get(0).fields.get("x-cos-meta-file-name"));
        assertEquals("app-release.apk", pgyer.uploads.get(0).fileName);
    }

    @Test
    void ignoresADownloadFileNameThatIsNotPlainAscii() throws Exception {
        client(pgyer.url()).upload(apk, options().setDownloadFileName("我的应用.apk"));

        assertEquals("app-release.apk", pgyer.uploads.get(0).fields.get("x-cos-meta-file-name"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("downloadFileName is ignored")), warnings.toString());
    }

    @Test
    void ignoresADownloadFileNameWithAPath() throws Exception {
        client(pgyer.url()).upload(apk, options().setDownloadFileName("dir/MyApp.apk"));

        assertEquals("app-release.apk", pgyer.uploads.get(0).fields.get("x-cos-meta-file-name"));
    }

    @Test
    void leavesOutTheFileNameMetadataForNonAsciiFileNames() throws Exception {
        Path chinese = Files.writeString(dir.resolve("应用-release.apk"), "APK-CONTENT");

        client(pgyer.url()).upload(chinese, options());

        Upload upload = pgyer.uploads.get(0);
        assertFalse(upload.fields.containsKey("x-cos-meta-file-name"));
        assertEquals("upload.apk", upload.fileName);
        assertArrayEquals("APK-CONTENT".getBytes(StandardCharsets.UTF_8), upload.fileContent);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204})
    void anySuccessfulStatusFromStorageIsAccepted(int status) throws Exception {
        pgyer.storageStatus = status;
        pgyer.storageBody = status == 204 ? "" : "<ok/>";

        UploadResult result = client(pgyer.url()).upload(apk, options());

        assertEquals(FakePgyerServer.BUILD_KEY, result.getBuildKey());
    }

    @Test
    void reportsStorageErrorsWithoutEchoingTheRequest() {
        pgyer.storageStatus = 403;
        pgyer.storageBody = "<?xml version='1.0' encoding='utf-8' ?><Error><Code>AccessDenied</Code>"
                + "<Message>Request has expired</Message><Resource>/" + FakePgyerServer.SIGNATURE + "</Resource>"
                + "<RequestId>NjU0ZjEx</RequestId><TraceId>" + FakePgyerServer.COS_TOKEN + "</TraceId></Error>";

        IOException e = assertThrows(IOException.class, () -> client(pgyer.url()).upload(apk, options()));

        assertEquals("Uploading the file to storage failed with HTTP 403: AccessDenied: Request has expired"
                + " (request ID NjU0ZjEx)", e.getMessage());
        assertTrue(pgyer.buildInfoRequests.isEmpty());
    }

    @Test
    void treatsBothProcessingCodesAsStillProcessing() throws Exception {
        pgyer.processingPolls = 3;
        pgyer.processingCode = 1246;

        client(pgyer.url()).upload(apk, options());

        assertEquals(4, pgyer.buildInfoRequests.size());
    }

    @Test
    void givesUpAfterTheProcessingTimeout() {
        pgyer.processingPolls = Integer.MAX_VALUE;

        IOException e = assertThrows(IOException.class,
                () -> client(pgyer.url()).upload(apk, options().setProcessingTimeout(Duration.ofMillis(300))));

        assertTrue(e.getMessage().startsWith("PGYER is still processing the build after 0 seconds."), e.getMessage());
        assertTrue(e.getMessage().endsWith("Build key: abc123.apk"), e.getMessage());
        assertTrue(pgyer.buildInfoRequests.size() > 1);
    }

    @Test
    void theDefaultProcessingTimeoutIsTenMinutes() {
        assertEquals(Duration.ofSeconds(600), new UploadOptions().getProcessingTimeout());
    }

    @Test
    void keepsPollingThroughNetworkErrors() throws Exception {
        pgyer.droppedBuildInfoCalls = 2;
        pgyer.htmlBuildInfoCalls = 2;
        pgyer.processingPolls = 1;

        UploadResult result = client(pgyer.url()).upload(apk, options());

        assertEquals(FakePgyerServer.BUILD_KEY, result.getBuildKey());
        assertEquals(6, pgyer.buildInfoRequests.size());
        long retryWarnings = warnings.stream().filter(w -> w.startsWith("[PGYER] Checking the build status failed")).count();
        assertEquals(1, retryWarnings, warnings.toString());
    }

    @Test
    void stopsPollingOnOtherErrors() {
        pgyer.buildInfoError = 1216;

        PgyerApiException e =
                assertThrows(PgyerApiException.class, () -> client(pgyer.url()).upload(apk, options()));

        assertEquals(1216, e.getCode());
        assertEquals("PGYER API error 1216: App failed to publish.", e.getMessage());
        assertEquals(1, pgyer.buildInfoRequests.size());
    }

    @Test
    void movesToTheNextServerWhenOneIsUnreachable() throws Exception {
        String down = FakePgyerServer.unreachableUrl();

        UploadResult result = client(down, pgyer.url()).upload(apk, options());

        assertEquals(FakePgyerServer.BUILD_KEY, result.getBuildKey());
        assertEquals(1, pgyer.tokenRequests.size());
        // Polls the server that handed out the token.
        assertEquals(1, pgyer.buildInfoRequests.size());
        assertTrue(warnings.get(0).startsWith("[PGYER] " + down + " is not reachable ("), warnings.toString());
        assertTrue(warnings.get(0).endsWith(", trying " + pgyer.url() + "."), warnings.toString());
    }

    @Test
    void movesToTheNextServerOnANonJsonAnswer() throws Exception {
        try (FakePgyerServer hijacked = new FakePgyerServer()) {
            hijacked.htmlTokenResponses = true;

            UploadResult result = client(hijacked.url(), pgyer.url()).upload(apk, options());

            assertEquals(FakePgyerServer.BUILD_KEY, result.getBuildKey());
            assertEquals(1, hijacked.tokenRequests.size());
            assertEquals(1, pgyer.tokenRequests.size());
            assertTrue(warnings.get(0).contains("(HTTP 200 with a non-JSON response)"), warnings.toString());
        }
    }

    @Test
    void doesNotMoveToTheNextServerOnAnApiError() throws Exception {
        try (FakePgyerServer first = new FakePgyerServer()) {
            first.tokenError = 1001;

            PgyerApiException e = assertThrows(PgyerApiException.class,
                    () -> client(first.url(), pgyer.url()).upload(apk, options()));

            assertEquals(1001, e.getCode());
            assertTrue(pgyer.tokenRequests.isEmpty());
        }
    }

    @Test
    void failsWhenNoServerCanBeReached() throws Exception {
        String a = FakePgyerServer.unreachableUrl();
        String b = FakePgyerServer.unreachableUrl();

        IOException e = assertThrows(IOException.class, () -> client(a, b).upload(apk, options()));

        assertTrue(e.getMessage().startsWith("None of the PGYER API servers could be reached: " + a + ", " + b),
                e.getMessage());
        assertEquals(2, warnings.size());
    }

    @Test
    void theDefaultServersAreTriedInOrder() {
        assertEquals(List.of("https://www.pgyer.com", "https://www.xcxwo.com", "https://www.pgyerapp.com"),
                PgyerClient.DEFAULT_API_SERVERS);
    }

    @Test
    void rejectsAnIncompleteTokenWithoutEchoingIt() {
        pgyer.incompleteToken = true;

        IOException e = assertThrows(IOException.class, () -> client(pgyer.url()).upload(apk, options()));

        assertEquals("PGYER returned an incomplete upload token. Please try again later.", e.getMessage());
        assertTrue(pgyer.uploads.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1001, 1002, 1003, 1019, 1020, 1022})
    void explainsErrorsUsersCanFix(int code) {
        pgyer.tokenError = code;

        PgyerApiException e = assertThrows(PgyerApiException.class, () -> client(pgyer.url()).upload(apk, options()));

        String[] lines = e.getMessage().split(System.lineSeparator());
        assertEquals("PGYER API error " + code + ": error from fake server", lines[0]);
        assertEquals(PgyerApiException.hint(code), lines[1]);
    }

    @Test
    void hints() {
        assertTrue(PgyerApiException.hint(1001).contains("https://www.pgyer.com/account/api"));
        assertEquals(PgyerApiException.hint(1001), PgyerApiException.hint(1002));
        assertTrue(PgyerApiException.hint(1003).contains("Sub-accounts"));
        assertTrue(PgyerApiException.hint(1019).contains("real-name verification"));
        assertTrue(PgyerApiException.hint(1020).contains("disabled"));
        assertTrue(PgyerApiException.hint(1022).contains("storage"));
        assertNull(PgyerApiException.hint(1216));
        assertEquals("PGYER API error 1216", new PgyerApiException(1216, " ").getMessage());
    }

    @Test
    void neverLogsSecrets() throws Exception {
        pgyer.processingPolls = 1;
        pgyer.droppedBuildInfoCalls = 1;
        String down = FakePgyerServer.unreachableUrl();
        UploadOptions options = options().setPassword(PASSWORD).setUpdateDescription("notes");

        client(down, pgyer.url()).upload(apk, options);

        String logs = allLogs();
        assertFalse(logs.isEmpty());
        for (String secret : List.of(API_KEY, PASSWORD, FakePgyerServer.SIGNATURE, FakePgyerServer.COS_TOKEN)) {
            assertFalse(logs.contains(secret), "log contains " + secret + ":\n" + logs);
        }
    }

    @Test
    void errorMessagesDoNotContainSecrets() throws Exception {
        List<String> messages = new ArrayList<>();
        pgyer.tokenError = 1001;
        messages.add(assertThrows(IOException.class, () -> client(pgyer.url()).upload(apk, options().setPassword(PASSWORD)))
                .getMessage());
        pgyer.tokenError = null;
        pgyer.storageStatus = 400;
        pgyer.storageBody = "<Error><Code>InvalidArgument</Code><Signature>" + FakePgyerServer.SIGNATURE + "</Signature></Error>";
        messages.add(assertThrows(IOException.class, () -> client(pgyer.url()).upload(apk, options())).getMessage());
        pgyer.storageStatus = 204;
        pgyer.storageBody = "";
        pgyer.buildInfoError = 1216;
        messages.add(assertThrows(IOException.class, () -> client(pgyer.url()).upload(apk, options())).getMessage());

        String all = String.join("\n", messages) + "\n" + allLogs();
        for (String secret : List.of(API_KEY, PASSWORD, FakePgyerServer.SIGNATURE, FakePgyerServer.COS_TOKEN)) {
            assertFalse(all.contains(secret), "contains " + secret + ":\n" + all);
        }
    }

    @Test
    void usesTheBuildKeyWhenThereIsNoShortcut() throws Exception {
        pgyer.withShortcut = false;

        UploadResult result = client(pgyer.url()).upload(apk, options());

        assertEquals("https://www.pgyer.com/abc123", result.getDownloadPageUrl());
        Map<String, Object> json = result.toJsonMap();
        assertEquals("https://www.pgyer.com/abc123", json.get("appPgyerURL"));
        assertEquals("https://www.pgyer.com/abc123", json.get("appBuildURL"));
        assertEquals("https://www.pgyer.com/app/qrcodeHistory/abc123", json.get("appQRCodeURL"));
        assertEquals("1.2.0", json.get("buildVersion"));
    }

    @Test
    void humanSizes() {
        assertEquals("0.0 KB", PgyerClient.humanSize(0));
        assertEquals("1.5 KB", PgyerClient.humanSize(1536));
        assertEquals("2.0 MB", PgyerClient.humanSize(2 * 1024 * 1024));
    }

    @Test
    void storageErrorParsing() {
        assertEquals("", PgyerClient.storageError(null));
        assertEquals("", PgyerClient.storageError("<html>nope</html>"));
        assertEquals(": EntityTooLarge", PgyerClient.storageError("<Error><Code>EntityTooLarge</Code></Error>"));
    }
}
