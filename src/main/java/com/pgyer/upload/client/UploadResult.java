package com.pgyer.upload.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The build information PGYER reports once an upload is published.
 */
public final class UploadResult {

    private final Map<String, Object> buildInfo;

    public UploadResult(Map<String, Object> buildInfo) {
        this.buildInfo = Collections.unmodifiableMap(new LinkedHashMap<>(buildInfo));
    }

    /** Everything {@code /apiv2/app/buildInfo} returned, such as {@code buildKey} and {@code buildVersion}. */
    public Map<String, Object> getBuildInfo() {
        return buildInfo;
    }

    /** A field of the build information as text, or {@code ""} when it is missing or null. */
    public String get(String field) {
        Object value = buildInfo.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    public String getBuildKey() {
        return get("buildKey");
    }

    /** The app's download page, which always shows its latest build. */
    public String getDownloadPageUrl() {
        String shortcut = get("buildShortcutUrl");
        return PgyerClient.WEB_SERVER + "/" + (shortcut.isEmpty() ? getBuildKey() : shortcut);
    }

    /** The page of this exact build. */
    public String getBuildPageUrl() {
        return PgyerClient.WEB_SERVER + "/" + getBuildKey();
    }

    /** An image of the QR code that opens the download page, or {@code ""}. */
    public String getQrCodeUrl() {
        return get("buildQRCodeURL");
    }

    /** For example {@code "My App 1.2.0 (build 7)"}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {get("buildName"), get("buildVersion")}) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        if (sb.length() == 0) {
            sb.append("the build");
        }
        String build = get("buildBuildVersion");
        if (!build.isEmpty()) {
            sb.append(" (build ").append(build).append(')');
        }
        return sb.toString();
    }

    /**
     * The build information plus {@code appPgyerURL} (download page), {@code appBuildURL} (this
     * build's page) and {@code appQRCodeURL}, the names the other PGYER integrations use.
     */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>(buildInfo);
        map.put("appPgyerURL", getDownloadPageUrl());
        map.put("appBuildURL", getBuildPageUrl());
        map.put("appQRCodeURL", getQrCodeUrl());
        return map;
    }
}
