package com.pgyer.upload;

import org.gradle.api.provider.Property;

/**
 * The upload settings shared by the {@code pgyer { }} extension and its per-variant blocks.
 *
 * <p>Settings that are not set are not sent, so PGYER keeps the app's current settings for them.
 */
public interface PgyerOptions {

    /**
     * The PGYER API key (https://www.pgyer.com/account/api). In the {@code pgyer { }} extension it
     * defaults to the {@code pgyer.apiKey} Gradle property, then the {@code PGYER_API_KEY}
     * environment variable. It is never logged.
     */
    Property<String> getApiKey();

    /** What changed in this build, shown on the download page. */
    Property<String> getUpdateDescription();

    /** The app description. */
    Property<String> getDescription();

    /**
     * Who can install: {@code "1"} or {@code "public"}, {@code "2"} or {@code "password"},
     * {@code "3"} or {@code "invite"}. When only {@link #getPassword() password} is set, the install
     * type is {@code 2}.
     */
    Property<String> getInstallType();

    /** The install password. It is never logged. */
    Property<String> getPassword();

    /** The first day the build can be installed, as {@code yyyy-MM-dd}. Set it with {@link #getInstallEndDate()}. */
    Property<String> getInstallStartDate();

    /** The last day the build can be installed, as {@code yyyy-MM-dd}. Set it with {@link #getInstallStartDate()}. */
    Property<String> getInstallEndDate();

    /** The short link of the download channel to publish the build to. */
    Property<String> getChannelShortcut();

    /**
     * {@code true} to store the build overseas, {@code false} for mainland China. Unset, PGYER chooses
     * by the IP address of the uploading machine.
     */
    Property<Boolean> getOversea();

    /** Seconds to wait for PGYER to process the build after the upload. Defaults to 600. */
    Property<Integer> getProcessingTimeout();

    /**
     * The file name people get when they download the build, including the extension. Plain ASCII
     * only. Defaults to the name of the uploaded file.
     */
    Property<String> getDownloadFileName();
}
