package com.pgyer.upload.client;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The settings of one upload. Only the settings that are set are sent, so PGYER keeps the app's
 * current settings for the rest.
 */
public class UploadOptions {

    /** Seconds to wait for PGYER to process an uploaded build, unless set otherwise. */
    public static final int DEFAULT_PROCESSING_TIMEOUT_SECONDS = 600;

    private String apiKey;
    private String buildType;
    private String installType;
    private String password;
    private String description;
    private String updateDescription;
    private String installStartDate;
    private String installEndDate;
    private String channelShortcut;
    private Boolean oversea;
    private String downloadFileName;
    private Duration processingTimeout = Duration.ofSeconds(DEFAULT_PROCESSING_TIMEOUT_SECONDS);

    public String getApiKey() {
        return apiKey;
    }

    public UploadOptions setApiKey(String apiKey) {
        this.apiKey = trimToNull(apiKey);
        return this;
    }

    public String getBuildType() {
        return buildType;
    }

    /** One of {@code android}, {@code ios} or {@code harmony}; see {@link #buildTypeOf(String)}. */
    public UploadOptions setBuildType(String buildType) {
        this.buildType = buildType;
        return this;
    }

    public String getInstallType() {
        return installType;
    }

    /**
     * {@code 1} (or {@code public}), {@code 2} ({@code password}) or {@code 3} ({@code invite}).
     *
     * @throws IllegalArgumentException for any other value
     */
    public UploadOptions setInstallType(String installType) {
        this.installType = parseInstallType(installType);
        return this;
    }

    public String getPassword() {
        return password;
    }

    public UploadOptions setPassword(String password) {
        this.password = blankToNull(password);
        return this;
    }

    public UploadOptions setDescription(String description) {
        this.description = trimToNull(description);
        return this;
    }

    public UploadOptions setUpdateDescription(String updateDescription) {
        this.updateDescription = trimToNull(updateDescription);
        return this;
    }

    /** Limits the install period. Dates are {@code yyyy-MM-dd}; set both or neither. */
    public UploadOptions setInstallPeriod(String startDate, String endDate) {
        this.installStartDate = trimToNull(startDate);
        this.installEndDate = trimToNull(endDate);
        return this;
    }

    public UploadOptions setChannelShortcut(String channelShortcut) {
        this.channelShortcut = trimToNull(channelShortcut);
        return this;
    }

    public Boolean getOversea() {
        return oversea;
    }

    /** {@code true} for overseas storage, {@code false} for mainland China, {@code null} lets PGYER choose. */
    public UploadOptions setOversea(Boolean oversea) {
        this.oversea = oversea;
        return this;
    }

    public String getDownloadFileName() {
        return downloadFileName;
    }

    /** The file name people get when they download the build. Defaults to the uploaded file's name. */
    public UploadOptions setDownloadFileName(String downloadFileName) {
        this.downloadFileName = trimToNull(downloadFileName);
        return this;
    }

    public Duration getProcessingTimeout() {
        return processingTimeout;
    }

    /** How long to wait for PGYER to process the build after the file is uploaded. */
    public UploadOptions setProcessingTimeout(Duration processingTimeout) {
        this.processingTimeout = processingTimeout;
        return this;
    }

    /**
     * Checks the settings before anything is sent.
     *
     * @throws IllegalArgumentException with a message for the user
     */
    public void validate() {
        if (apiKey == null) {
            throw new IllegalArgumentException("The PGYER API key is not set.");
        }
        if (buildType == null) {
            throw new IllegalArgumentException("The build type is not set.");
        }
        if ((installStartDate == null) != (installEndDate == null)) {
            throw new IllegalArgumentException("Set both installStartDate and installEndDate, or neither.");
        }
        if (installStartDate != null) {
            LocalDate start = parseDate("installStartDate", installStartDate);
            LocalDate end = parseDate("installEndDate", installEndDate);
            if (end.isBefore(start)) {
                throw new IllegalArgumentException(
                        "installEndDate (" + installEndDate + ") is before installStartDate (" + installStartDate
                                + ").");
            }
        }
        if (processingTimeout == null || processingTimeout.isNegative() || processingTimeout.isZero()) {
            throw new IllegalArgumentException("processingTimeout must be a positive number of seconds.");
        }
    }

    /** The form for {@code /apiv2/app/getCOSToken}. */
    Map<String, String> toTokenForm() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("_api_key", apiKey);
        form.put("buildType", buildType);
        String type = installType;
        if (type == null && password != null) {
            // A password on its own only makes sense for password-protected installs.
            type = "2";
        }
        putIfSet(form, "buildInstallType", type);
        putIfSet(form, "buildPassword", password);
        putIfSet(form, "buildDescription", description);
        putIfSet(form, "buildUpdateDescription", updateDescription);
        if (installStartDate != null && installEndDate != null) {
            form.put("buildInstallDate", "1");
            form.put("buildInstallStartDate", installStartDate);
            form.put("buildInstallEndDate", installEndDate);
        }
        putIfSet(form, "buildChannelShortcut", channelShortcut);
        if (oversea != null) {
            // 1 = overseas, 2 = mainland China; without it the server chooses by the client's IP address.
            form.put("oversea", oversea ? "1" : "2");
        }
        return form;
    }

    /**
     * The PGYER build type for an app file, from its extension.
     *
     * @throws IllegalArgumentException if the file is not an {@code .apk}, {@code .ipa} or {@code .hap}
     */
    public static String buildTypeOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".apk")) {
            return "android";
        }
        if (name.endsWith(".ipa")) {
            return "ios";
        }
        if (name.endsWith(".hap")) {
            return "harmony";
        }
        throw new IllegalArgumentException(
                "Cannot tell the app type of " + fileName + ". PGYER accepts .apk, .ipa and .hap files.");
    }

    /**
     * Normalizes an install type to {@code "1"}, {@code "2"}, {@code "3"} or {@code null} when not set.
     *
     * @throws IllegalArgumentException for an unknown value
     */
    public static String parseInstallType(String value) {
        String v = trimToNull(value);
        if (v == null) {
            return null;
        }
        switch (v.toLowerCase(Locale.ROOT)) {
            case "1":
            case "public":
                return "1";
            case "2":
            case "password":
                return "2";
            case "3":
            case "invite":
            case "invitation":
                return "3";
            default:
                throw new IllegalArgumentException(
                        "installType must be 1 (public), 2 (password) or 3 (invite), not \"" + v + "\".");
        }
    }

    /** Printable ASCII only: COS stores {@code x-cos-meta-*} fields as HTTP headers. */
    static boolean isPlainAscii(String text) {
        return text != null && !text.isEmpty() && text.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
    }

    private static LocalDate parseDate(String name, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a date like 2026-12-31, not \"" + value + "\".");
        }
    }

    private static void putIfSet(Map<String, String> form, String key, String value) {
        if (value != null) {
            form.put(key, value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
