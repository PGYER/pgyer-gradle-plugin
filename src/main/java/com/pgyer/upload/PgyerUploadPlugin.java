package com.pgyer.upload;

import com.pgyer.upload.internal.AndroidSupport;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

/**
 * The {@code com.pgyer.upload} plugin. Adds the {@code pgyer { }} extension, makes its settings the
 * defaults of every PGYER upload task, and with the Android application plugin creates an
 * {@code uploadPgyer<Variant>} task for each variant.
 */
public abstract class PgyerUploadPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        PgyerExtension extension = project.getExtensions().create("pgyer", PgyerExtension.class);
        // Filled before each variant task is registered, so the lookup below does not depend on the
        // order in which Gradle runs configuration actions.
        Map<String, PgyerVariantSettings> variantSettings = new ConcurrentHashMap<>();

        project.getTasks().withType(AbstractPgyerUploadTask.class).configureEach(task -> {
            PgyerOptions v = variantSettings.get(task.getName());
            PgyerOptions e = extension;
            connect(task.getApiKey(), v == null ? null : v.getApiKey(), e.getApiKey());
            connect(task.getUpdateDescription(), v == null ? null : v.getUpdateDescription(), e.getUpdateDescription());
            connect(task.getAppDescription(), v == null ? null : v.getDescription(), e.getDescription());
            connect(task.getInstallType(), v == null ? null : v.getInstallType(), e.getInstallType());
            connect(task.getPassword(), v == null ? null : v.getPassword(), e.getPassword());
            connect(task.getInstallStartDate(), v == null ? null : v.getInstallStartDate(), e.getInstallStartDate());
            connect(task.getInstallEndDate(), v == null ? null : v.getInstallEndDate(), e.getInstallEndDate());
            connect(task.getChannelShortcut(), v == null ? null : v.getChannelShortcut(), e.getChannelShortcut());
            connect(task.getOversea(), v == null ? null : v.getOversea(), e.getOversea());
            connect(task.getProcessingTimeout(), v == null ? null : v.getProcessingTimeout(), e.getProcessingTimeout());
            connect(task.getDownloadFileName(), v == null ? null : v.getDownloadFileName(), e.getDownloadFileName());
        });

        project.getPluginManager().withPlugin("com.android.application", applied ->
                AndroidSupport.register(project, extension, v -> extension.getVariantFilter().isSatisfiedBy(v),
                        variantSettings));
    }

    /** Makes the variant setting, then the extension setting, the task property's convention. */
    private static <T> void connect(Property<T> task, Property<T> variant, Provider<T> extension) {
        task.convention(variant == null ? extension : variant.orElse(extension));
    }
}
