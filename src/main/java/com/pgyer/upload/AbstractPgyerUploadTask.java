package com.pgyer.upload;

import com.pgyer.upload.client.Json;
import com.pgyer.upload.client.PgyerClient;
import com.pgyer.upload.client.PgyerLog;
import com.pgyer.upload.client.UploadOptions;
import com.pgyer.upload.client.UploadResult;
import com.pgyer.upload.internal.PluginInfo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.util.GradleVersion;

/**
 * Uploads an app file to PGYER and waits until the build is published. Subclasses decide which file.
 *
 * <p>Uploading is a side effect, so these tasks are never up to date and never cached. The API key
 * and install password are {@link Internal}: they are not task inputs and are never logged.
 */
@UntrackedTask(because = "Every run publishes a new build to PGYER")
public abstract class AbstractPgyerUploadTask extends DefaultTask {

    /** The Gradle property that replaces the PGYER API servers, comma-separated. For testing. */
    public static final String API_SERVERS_PROPERTY = "pgyer.apiServers";

    public AbstractPgyerUploadTask() {
        setGroup("pgyer");
        ProviderFactory providers = getProviders();
        getApiKey().convention(providers.gradleProperty(PgyerExtension.API_KEY_PROPERTY)
                .orElse(providers.environmentVariable(PgyerExtension.API_KEY_VARIABLE)));
        getProcessingTimeout().convention(UploadOptions.DEFAULT_PROCESSING_TIMEOUT_SECONDS);
        getApiServers().convention(providers.gradleProperty(API_SERVERS_PROPERTY)
                .map(AbstractPgyerUploadTask::splitServers)
                // A plain ArrayList: the configuration cache of Gradle 8 cannot restore List.of() values.
                .orElse(new ArrayList<>(PgyerClient.DEFAULT_API_SERVERS)));
        getResultFile().convention(getLayout().getBuildDirectory().file("outputs/pgyer/" + getName() + ".json"));
    }

    @Inject
    protected abstract ProviderFactory getProviders();

    @Inject
    protected abstract ProjectLayout getLayout();

    /** The PGYER API key. Not a task input and never logged. */
    @Internal
    public abstract Property<String> getApiKey();

    /** What changed in this build, shown on the download page. */
    @Input
    @Optional
    public abstract Property<String> getUpdateDescription();

    /** The app description ({@code description} in the {@code pgyer { }} extension). */
    @Input
    @Optional
    public abstract Property<String> getAppDescription();

    /** {@code 1}/{@code public}, {@code 2}/{@code password} or {@code 3}/{@code invite}. */
    @Input
    @Optional
    public abstract Property<String> getInstallType();

    /** The install password. Not a task input and never logged. */
    @Internal
    public abstract Property<String> getPassword();

    /** {@code yyyy-MM-dd}. */
    @Input
    @Optional
    public abstract Property<String> getInstallStartDate();

    /** {@code yyyy-MM-dd}. */
    @Input
    @Optional
    public abstract Property<String> getInstallEndDate();

    @Input
    @Optional
    public abstract Property<String> getChannelShortcut();

    /** {@code true} overseas, {@code false} mainland China, unset lets PGYER choose. */
    @Input
    @Optional
    public abstract Property<Boolean> getOversea();

    /** Seconds to wait for PGYER to process the build. Defaults to 600. */
    @Input
    public abstract Property<Integer> getProcessingTimeout();

    @Input
    @Optional
    public abstract Property<String> getDownloadFileName();

    /** The PGYER API servers, tried in order when one cannot be reached. */
    @Internal
    public abstract ListProperty<String> getApiServers();

    /**
     * Where the build information is written as JSON. Defaults to
     * {@code build/outputs/pgyer/<variant or task name>.json}.
     */
    @OutputFile
    public abstract RegularFileProperty getResultFile();

    /** The file to upload. Called when the task runs. */
    protected abstract File resolveUploadFile();

    @TaskAction
    public void upload() throws InterruptedException {
        File file = resolveUploadFile();
        if (!file.isFile()) {
            throw new InvalidUserDataException("The file to upload does not exist: " + file);
        }
        if (file.length() == 0) {
            throw new InvalidUserDataException("The file to upload is empty: " + file);
        }
        String apiKey = getApiKey().getOrNull();
        if (apiKey == null || apiKey.isBlank()) {
            throw new InvalidUserDataException("No PGYER API key. Set the " + PgyerExtension.API_KEY_VARIABLE
                    + " environment variable, the " + PgyerExtension.API_KEY_PROPERTY
                    + " Gradle property (for example in ~/.gradle/gradle.properties) or pgyer { apiKey = ... }."
                    + " Find your key at https://www.pgyer.com/account/api");
        }

        UploadOptions options = new UploadOptions();
        try {
            options.setApiKey(apiKey)
                    .setBuildType(UploadOptions.buildTypeOf(file.getName()))
                    .setInstallType(getInstallType().getOrNull())
                    .setPassword(getPassword().getOrNull())
                    .setDescription(getAppDescription().getOrNull())
                    .setUpdateDescription(getUpdateDescription().getOrNull())
                    .setInstallPeriod(getInstallStartDate().getOrNull(), getInstallEndDate().getOrNull())
                    .setChannelShortcut(getChannelShortcut().getOrNull())
                    .setOversea(getOversea().getOrNull())
                    .setDownloadFileName(getDownloadFileName().getOrNull())
                    .setProcessingTimeout(Duration.ofSeconds(getProcessingTimeout().get()));
            options.validate();
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException(e.getMessage(), e);
        }
        Logger logger = getLogger();
        if (options.getPassword() != null && options.getInstallType() != null && !"2".equals(options.getInstallType())) {
            logger.warn("[PGYER] The install password is only used when installType is 2 (password).");
        }
        logger.info("[PGYER] App file: {} ({})", file, options.getBuildType());
        logger.info("[PGYER] Settings: {}", describeSettings(options));

        PgyerClient client = new PgyerClient(getApiServers().get(), userAgent(), new GradleLog(logger));
        UploadResult result;
        try {
            result = client.upload(file.toPath(), options);
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException(e.getMessage(), e);
        } catch (IOException e) {
            throw new GradleException("Uploading to PGYER failed. " + e.getMessage(), e);
        }

        Path out = getResultFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, Json.write(result.toJsonMap()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("Could not write " + out + ": " + e.getMessage(), e);
        }
        logger.lifecycle("[PGYER] Published {}", result.describe());
        logger.lifecycle("[PGYER] Download page: {}", result.getDownloadPageUrl());
        if (!result.getQrCodeUrl().isEmpty()) {
            logger.lifecycle("[PGYER] QR code: {}", result.getQrCodeUrl());
        }
        logger.info("[PGYER] Build information written to {}", out);
    }

    /** {@code pgyer-gradle-plugin/<version> Gradle/<version> Java/<version>}; PGYER sorts uploads by the first token. */
    static String userAgent() {
        return "pgyer-gradle-plugin/" + PluginInfo.version() + " Gradle/" + GradleVersion.current().getVersion()
                + " Java/" + System.getProperty("java.version");
    }

    private String describeSettings(UploadOptions options) {
        List<String> settings = new ArrayList<>();
        String installType = options.getInstallType() != null
                ? options.getInstallType()
                : options.getPassword() != null ? "2" : null;
        if (installType != null) {
            settings.add("install type " + installType);
        }
        if (options.getPassword() != null) {
            settings.add("install password set");
        }
        if (getInstallStartDate().isPresent()) {
            settings.add("install period " + getInstallStartDate().get() + " to " + getInstallEndDate().getOrElse(""));
        }
        if (getChannelShortcut().isPresent()) {
            settings.add("channel " + getChannelShortcut().get());
        }
        if (options.getOversea() != null) {
            settings.add("oversea " + options.getOversea());
        }
        if (getAppDescription().isPresent()) {
            settings.add("description set");
        }
        if (getUpdateDescription().isPresent()) {
            settings.add("update description set");
        }
        if (options.getDownloadFileName() != null) {
            settings.add("download file name " + options.getDownloadFileName());
        }
        settings.add("processing timeout " + getProcessingTimeout().get() + "s");
        return String.join(", ", settings);
    }

    private static List<String> splitServers(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(s -> s.replaceAll("/+$", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static final class GradleLog implements PgyerLog {
        private final Logger logger;

        GradleLog(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void lifecycle(String message) {
            logger.lifecycle(message);
        }

        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void warn(String message) {
            logger.warn(message);
        }
    }
}
