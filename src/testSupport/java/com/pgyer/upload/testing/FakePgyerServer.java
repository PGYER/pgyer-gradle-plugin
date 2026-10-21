package com.pgyer.upload.testing;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for the PGYER API and the COS bucket it hands out upload tokens for.
 */
public class FakePgyerServer implements AutoCloseable {

    public static final String BUILD_KEY = "abc123";
    public static final String SHORTCUT = "myapp";
    /** Secrets the server hands out; they must never show up in logs. */
    public static final String SIGNATURE = "SIG-SECRET-7d1e";
    public static final String COS_TOKEN = "COS-TOKEN-SECRET-9f3a";

    /** A request as the server received it. */
    public static final class Request {
        public final String method;
        public final String uri;
        public final Map<String, String> headers;
        public final Map<String, String> form;

        Request(String method, String uri, Map<String, String> headers, Map<String, String> form) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.form = form;
        }
    }

    /** A multipart upload as the storage endpoint received it. */
    public static final class Upload {
        public final Map<String, String> headers;
        public final Map<String, String> fields = new LinkedHashMap<>();
        public String fileName;
        public byte[] fileContent;
        public long bodyLength;

        Upload(Map<String, String> headers) {
            this.headers = headers;
        }
    }

    private final HttpServer server;

    public final List<Request> tokenRequests = new CopyOnWriteArrayList<>();
    public final List<Upload> uploads = new CopyOnWriteArrayList<>();
    public final List<Request> buildInfoRequests = new CopyOnWriteArrayList<>();

    /** Number of buildInfo calls answered with "still processing" before the build is published. */
    public volatile int processingPolls;
    /** The "still processing" code, 1246 or 1247. */
    public volatile int processingCode = 1247;
    /** Number of buildInfo calls whose connection is dropped without an answer. */
    public volatile int droppedBuildInfoCalls;
    /** Number of buildInfo calls answered with an HTML page. */
    public volatile int htmlBuildInfoCalls;
    /** When set, getCOSToken answers with this error code. */
    public volatile Integer tokenError;
    /** When set, buildInfo answers with this error code instead of publishing. */
    public volatile Integer buildInfoError;
    /** When true, getCOSToken answers with an HTML page, as a hijacked DNS record would. */
    public volatile boolean htmlTokenResponses;
    /** When true, getCOSToken answers without upload parameters. */
    public volatile boolean incompleteToken;
    /** The storage endpoint's HTTP status. */
    public volatile int storageStatus = 204;
    /** The storage endpoint's body. */
    public volatile String storageBody = "";
    /** Without a shortcut, the download page uses the build key. */
    public volatile boolean withShortcut = true;

    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    public FakePgyerServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apiv2/app/getCOSToken", this::token);
        server.createContext("/cos", this::storage);
        server.createContext("/apiv2/app/buildInfo", this::buildInfo);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** An address nothing listens on. */
    public static String unreachableUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.add(record(exchange));
        if (htmlTokenResponses) {
            respond(exchange, 200, "text/html", "<html><body>Welcome to our Wi-Fi</body></html>");
            return;
        }
        if (tokenError != null) {
            respond(exchange, 200, "application/json",
                    "{\"code\":" + tokenError + ",\"message\":\"error from fake server\"}");
            return;
        }
        String key = BUILD_KEY + ".apk";
        String params = incompleteToken
                ? "null"
                : "{\"key\":\"" + key + "\",\"signature\":\"" + SIGNATURE + "\",\"x-cos-security-token\":\""
                        + COS_TOKEN + "\"}";
        respond(exchange, 200, "application/json",
                "{\"code\":0,\"message\":\"\",\"data\":{\"params\":" + params + ",\"key\":\"" + key
                        + "\",\"endpoint\":\"" + url() + "/cos\"}}");
    }

    private void storage(HttpExchange exchange) throws IOException {
        Upload upload = new Upload(headers(exchange.getRequestHeaders()));
        byte[] body = exchange.getRequestBody().readAllBytes();
        upload.bodyLength = body.length;
        parseMultipart(upload, exchange.getRequestHeaders().getFirst("Content-Type"), body);
        uploads.add(upload);
        if (storageBody.isEmpty()) {
            exchange.sendResponseHeaders(storageStatus, -1);
            exchange.close();
        } else {
            respond(exchange, storageStatus, "application/xml", storageBody);
        }
    }

    private void buildInfo(HttpExchange exchange) throws IOException {
        buildInfoRequests.add(record(exchange));
        int call = buildInfoCalls.incrementAndGet();
        if (call <= droppedBuildInfoCalls) {
            // Close the connection without an answer, like a network failure.
            throw new IOException("dropping the connection");
        }
        if (call <= droppedBuildInfoCalls + htmlBuildInfoCalls) {
            respond(exchange, 502, "text/html", "<html><body>Bad Gateway</body></html>");
            return;
        }
        if (buildInfoError != null) {
            respond(exchange, 200, "application/json",
                    "{\"code\":" + buildInfoError + ",\"message\":\"App failed to publish.\"}");
            return;
        }
        if (polls.incrementAndGet() <= processingPolls) {
            respond(exchange, 200, "application/json",
                    "{\"code\":" + processingCode + ",\"message\":\"App is being processed. Please call this API later\"}");
            return;
        }
        respond(exchange, 200, "application/json",
                "{\"code\":0,\"message\":\"\",\"data\":{"
                        + "\"buildKey\":\"" + BUILD_KEY + "\",\"buildType\":\"2\",\"buildName\":\"My App\","
                        + "\"buildVersion\":\"1.2.0\",\"buildBuildVersion\":\"7\",\"buildFileSize\":\"11\","
                        + "\"buildShortcutUrl\":\"" + (withShortcut ? SHORTCUT : "") + "\","
                        + "\"buildDescription\":null,\"buildCreated\":\"2026-10-21 10:00:00\","
                        + "\"buildQRCodeURL\":\"https://www.pgyer.com/app/qrcodeHistory/" + BUILD_KEY + "\"}}");
    }

    private static Request record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new LinkedHashMap<>();
        if (!body.isEmpty()) {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                headers(exchange.getRequestHeaders()), form);
    }

    private static Map<String, String> headers(Headers headers) {
        Map<String, String> map = new LinkedHashMap<>();
        headers.forEach((name, values) -> map.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
        return map;
    }

    private static final Pattern DISPOSITION =
            Pattern.compile("Content-Disposition: form-data; name=\"([^\"]*)\"(?:; filename=\"([^\"]*)\")?");

    private static void parseMultipart(Upload upload, String contentType, byte[] body) {
        if (contentType == null || !contentType.contains("boundary=")) {
            return;
        }
        String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + 9);
        String text = new String(body, StandardCharsets.ISO_8859_1);
        for (String part : text.split(Pattern.quote(boundary))) {
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                continue;
            }
            Matcher m = DISPOSITION.matcher(part.substring(0, headerEnd));
            if (!m.find()) {
                continue;
            }
            String value = part.substring(headerEnd + 4, part.length() - 2); // strip the trailing CRLF
            if (m.group(2) != null) {
                upload.fileName = m.group(2);
                upload.fileContent = value.getBytes(StandardCharsets.ISO_8859_1);
            } else {
                upload.fields.put(m.group(1), new String(value.getBytes(StandardCharsets.ISO_8859_1),
                        StandardCharsets.UTF_8));
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
