package com.pgyer.upload.client;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uploads a build with the PGYER API: get a COS upload token, send the file to COS, then wait until
 * PGYER has published the build.
 *
 * <p>The API key is only ever sent in POST bodies, and neither it, the install password nor the
 * storage credentials are logged or put into exception messages.
 */
public class PgyerClient {

    /** Tried in order; the later ones help when DNS for the main domain is blocked or hijacked. */
    public static final List<String> DEFAULT_API_SERVERS =
            List.of("https://www.pgyer.com", "https://www.xcxwo.com", "https://www.pgyerapp.com");

    /** Where download pages live. */
    public static final String WEB_SERVER = "https://www.pgyer.com";

    private static final int CODE_SYNCING = 1246;
    private static final int CODE_PROCESSING = 1247;
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);

    private final List<String> apiServers;
    private final String userAgent;
    private final PgyerLog log;
    private final HttpClient http;
    private Duration pollInterval = Duration.ofSeconds(3);

    /**
     * @param apiServers PGYER API servers to try in order, such as {@link #DEFAULT_API_SERVERS}
     * @param userAgent the User-Agent header for every request
     * @param log where progress is reported
     */
    public PgyerClient(List<String> apiServers, String userAgent, PgyerLog log) {
        if (apiServers.isEmpty()) {
            throw new IllegalArgumentException("No PGYER API servers given");
        }
        this.apiServers = List.copyOf(apiServers);
        this.userAgent = userAgent;
        this.log = log;
        // Without an explicit proxy selector the client uses the JVM's default one, which follows the
        // http(s).proxyHost and http.nonProxyHosts system properties (systemProp.* in gradle.properties).
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER);
        Authenticator proxyAuth = proxyAuthenticator();
        if (proxyAuth != null) {
            builder.authenticator(proxyAuth);
        }
        this.http = builder.build();
    }

    void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    /**
     * Uploads {@code file} and waits until PGYER has published it.
     *
     * @throws PgyerApiException when PGYER answers with an error code
     * @throws IOException when the upload fails or PGYER takes longer than the processing timeout
     * @throws IllegalArgumentException when the options are invalid
     */
    public UploadResult upload(Path file, UploadOptions options) throws IOException, InterruptedException {
        options.validate();
        Token token = requestToken(options.toTokenForm());
        sendToStorage(token, file, options);
        log.lifecycle("[PGYER] Upload finished. Waiting for PGYER to process the build...");
        return new UploadResult(
                waitForBuild(token.server, options.getApiKey(), token.key, options.getProcessingTimeout()));
    }

    private Token requestToken(Map<String, String> form) throws IOException, InterruptedException {
        NetworkException lastFailure = null;
        for (int i = 0; i < apiServers.size(); i++) {
            String server = apiServers.get(i);
            Map<String, Object> json;
            try {
                json = postForm(server + "/apiv2/app/getCOSToken", form);
            } catch (NetworkException e) {
                // Only network failures and non-API answers move on; API errors are final.
                lastFailure = e;
                String next = i + 1 < apiServers.size() ? ", trying " + apiServers.get(i + 1) : "";
                log.warn("[PGYER] " + server + " is not reachable (" + e.getMessage() + ")" + next + ".");
                continue;
            }
            int code = codeOf(json);
            if (code != 0) {
                throw new PgyerApiException(code, messageOf(json));
            }
            Object data = json.get("data");
            if (data instanceof Map) {
                Map<?, ?> d = (Map<?, ?>) data;
                Object params = d.get("params");
                Object endpoint = d.get("endpoint");
                Object key = d.get("key");
                if (params instanceof Map && endpoint instanceof String && !((String) endpoint).isEmpty()
                        && key != null && !String.valueOf(key).isEmpty()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) params).entrySet()) {
                        fields.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    return new Token(server, (String) endpoint, String.valueOf(key), fields);
                }
            }
            // Never echo the answer: it holds the storage signature and token.
            throw new IOException("PGYER returned an incomplete upload token. Please try again later.");
        }
        throw new IOException("None of the PGYER API servers could be reached: " + String.join(", ", apiServers)
                + ". Last error: " + (lastFailure == null ? "unknown" : lastFailure.getMessage()), lastFailure);
    }

    private void sendToStorage(Token token, Path file, UploadOptions options) throws IOException, InterruptedException {
        long size = Files.size(file);
        String fileName = String.valueOf(file.getFileName());
        String boundary = "----PgyerGradle" + UUID.randomUUID().toString().replace("-", "");

        Map<String, String> fields = new LinkedHashMap<>(token.fields);
        String downloadName = fileName;
        String requested = options.getDownloadFileName();
        if (requested != null) {
            if (UploadOptions.isPlainAscii(requested) && requested.indexOf('/') < 0 && requested.indexOf('\\') < 0) {
                downloadName = requested;
            } else {
                log.warn("[PGYER] downloadFileName is ignored: it must be a plain ASCII file name without '/' or '\\'.");
            }
        }
        // COS stores x-cos-meta-* fields as HTTP headers, so only plain ASCII names are safe to send.
        if (UploadOptions.isPlainAscii(downloadName)) {
            fields.put("x-cos-meta-file-name", downloadName);
        }

        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(field.getKey()).append("\"\r\n\r\n")
                    .append(field.getValue()).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(multipartFileName(fileName)).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n");
        String tail = "\r\n--" + boundary + "--\r\n";

        // The file is streamed from disk; only the small multipart head and tail are held in memory.
        BodyPublisher body = BodyPublishers.concat(
                BodyPublishers.ofString(head.toString(), StandardCharsets.UTF_8),
                withProgress(BodyPublishers.ofFile(file), size),
                BodyPublishers.ofString(tail, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(token.endpoint))
                .header("User-Agent", userAgent)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();

        log.lifecycle("[PGYER] Uploading " + fileName + " (" + humanSize(size) + ")...");
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException("Uploading the file to storage failed: " + describe(e), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Uploading the file to storage failed with HTTP " + response.statusCode()
                    + storageError(response.body()));
        }
    }

    private Map<String, Object> waitForBuild(String server, String apiKey, String buildKey, Duration timeout)
            throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("_api_key", apiKey);
        form.put("buildKey", buildKey);
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean reportedNetworkError = false;
        while (true) {
            Map<String, Object> json = null;
            try {
                json = postForm(server + "/apiv2/app/buildInfo", form);
            } catch (NetworkException e) {
                // The file is already uploaded, so keep polling through short network problems.
                if (!reportedNetworkError) {
                    log.warn("[PGYER] Checking the build status failed (" + e.getMessage() + "), retrying...");
                    reportedNetworkError = true;
                }
            }
            if (json != null) {
                int code = codeOf(json);
                if (code == 0) {
                    Object data = json.get("data");
                    if (!(data instanceof Map)) {
                        throw new IOException(
                                "PGYER published the build but returned no build information. Build key: " + buildKey);
                    }
                    Map<String, Object> info = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) data).entrySet()) {
                        info.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    return info;
                }
                if (code != CODE_SYNCING && code != CODE_PROCESSING) {
                    throw new PgyerApiException(code, messageOf(json));
                }
                log.info("[PGYER] PGYER is still processing the build...");
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IOException("PGYER is still processing the build after " + timeout.toSeconds()
                        + " seconds. It may still be published; check your app list on PGYER. Build key: " + buildKey);
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /** Posts a form to the PGYER API. Network failures and answers that are not PGYER JSON throw NetworkException. */
    private Map<String, Object> postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(API_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new NetworkException(describe(e), e);
        }
        Object json;
        try {
            json = Json.parse(response.body());
        } catch (Json.ParseException e) {
            // Usually a captive portal, a hijacked DNS record or a proxy error page.
            throw new NetworkException("HTTP " + response.statusCode() + " with a non-JSON response", e);
        }
        if (!(json instanceof Map) || !((Map<?, ?>) json).containsKey("code")) {
            throw new NetworkException("HTTP " + response.statusCode() + " with an unexpected response", null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) json;
        return map;
    }

    private static int codeOf(Map<String, Object> json) {
        Object code = json.get("code");
        if (code instanceof Number) {
            return ((Number) code).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(code).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String messageOf(Map<String, Object> json) {
        Object message = json.get("message");
        return message == null ? null : String.valueOf(message);
    }

    private BodyPublisher withProgress(BodyPublisher file, long size) {
        AtomicLong sent = new AtomicLong();
        AtomicLong lastReported = new AtomicLong();
        return BodyPublishers.fromPublisher(
                subscriber -> file.subscribe(new Flow.Subscriber<ByteBuffer>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscriber.onSubscribe(subscription);
                    }

                    @Override
                    public void onNext(ByteBuffer item) {
                        long total = sent.addAndGet(item.remaining());
                        long tenth = size == 0 ? 10 : total * 10 / size;
                        if (tenth > lastReported.get()) {
                            lastReported.set(tenth);
                            log.lifecycle("[PGYER] Uploaded " + tenth * 10 + "% (" + humanSize(total) + ")");
                        }
                        subscriber.onNext(item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        subscriber.onError(throwable);
                    }

                    @Override
                    public void onComplete() {
                        subscriber.onComplete();
                    }
                }),
                size);
    }

    /** The multipart file name; COS ignores it, but it must be safe to put in a header. */
    private static String multipartFileName(String name) {
        if (UploadOptions.isPlainAscii(name) && name.indexOf('"') < 0) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        return "upload" + (dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "");
    }

    private static final Pattern XML_TAG = Pattern.compile("<(Code|Message|RequestId)>([^<]{0,200})</\\1>");

    /** Picks the error code, message and request ID out of a COS XML error, without echoing anything else. */
    static String storageError(String body) {
        if (body == null) {
            return "";
        }
        Map<String, String> tags = new LinkedHashMap<>();
        Matcher m = XML_TAG.matcher(body);
        while (m.find()) {
            tags.putIfAbsent(m.group(1), m.group(2).trim());
        }
        StringBuilder sb = new StringBuilder();
        for (String name : new String[] {"Code", "Message"}) {
            String value = tags.get(name);
            if (value != null && !value.isEmpty()) {
                sb.append(": ").append(value);
            }
        }
        String requestId = tags.get("RequestId");
        if (requestId != null && !requestId.isEmpty()) {
            sb.append(" (request ID ").append(requestId).append(')');
        }
        return sb.toString();
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            Throwable cause = e.getCause();
            message = cause != null && cause.getMessage() != null ? cause.getMessage() : e.getClass().getSimpleName();
        }
        return message;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * Answers proxy authentication challenges with the standard {@code https.proxyUser} /
     * {@code https.proxyPassword} (or {@code http.*}) system properties, as Gradle itself does.
     */
    private static Authenticator proxyAuthenticator() {
        String httpsUser = System.getProperty("https.proxyUser");
        String httpUser = System.getProperty("http.proxyUser");
        if (isBlank(httpsUser) && isBlank(httpUser)) {
            return null;
        }
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                boolean https = !isBlank(httpsUser);
                String user = https ? httpsUser : httpUser;
                String password = System.getProperty(https ? "https.proxyPassword" : "http.proxyPassword", "");
                return new PasswordAuthentication(user, password.toCharArray());
            }
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** The server could not be reached or did not answer with PGYER API JSON. */
    static final class NetworkException extends IOException {
        private static final long serialVersionUID = 1L;

        NetworkException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Token {
        final String server;
        final String endpoint;
        final String key;
        final Map<String, String> fields;

        Token(String server, String endpoint, String key, Map<String, String> fields) {
            this.server = server;
            this.endpoint = endpoint;
            this.key = key;
            this.fields = fields;
        }
    }
}
