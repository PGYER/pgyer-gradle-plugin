package com.pgyer.upload.internal;

import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.pgyer.upload.PgyerAndroidUploadTask;
import com.pgyer.upload.PgyerExtension;
import com.pgyer.upload.PgyerVariant;
import com.pgyer.upload.PgyerVariantSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import kotlin.Pair;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.ProjectLayout;

/**
 * Creates an {@code uploadPgyer<Variant>} task for each Android application variant. Only loaded
 * once the Android application plugin is applied, so the plugin works without AGP on the classpath.
 */
public final class AndroidSupport {

    private AndroidSupport() {}

    /**
     * @param variantSettings filled with the per-variant settings of each created task, by task name
     */
    public static void register(
            Project project,
            PgyerExtension extension,
            Predicate<PgyerVariant> filter,
            Map<String, PgyerVariantSettings> variantSettings) {
        ApplicationAndroidComponentsExtension components =
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
        ProjectLayout layout = project.getLayout();
        Set<String> variantNames = new LinkedHashSet<>();
        components.onVariants(components.selector().all(), (Action<ApplicationVariant>) variant -> {
            String name = variant.getName();
            variantNames.add(name);
            PgyerVariantSettings settings = extension.getVariants().findByName(name);
            if (settings != null && !settings.getEnabled().getOrElse(true)) {
                project.getLogger().info("[PGYER] Uploads are disabled for the {} variant.", name);
                return;
            }
            if (!filter.test(new Info(variant))) {
                return;
            }
            String taskName = taskName(name);
            if (settings != null) {
                variantSettings.put(taskName, settings);
            }
            project.getTasks().register(taskName, PgyerAndroidUploadTask.class, task -> {
                task.setDescription("Builds the " + name + " APK and uploads it to PGYER.");
                task.getVariantName().set(name);
                task.getApkDirectory().set(variant.getArtifacts().get(SingleArtifact.APK.INSTANCE));
                task.getBuiltArtifactsLoader().set(variant.getArtifacts().getBuiltArtifactsLoader());
                task.getResultFile().convention(layout.getBuildDirectory().file("outputs/pgyer/" + name + ".json"));
            });
        });
        // AGP creates the variants in an afterEvaluate hook registered before this one.
        project.afterEvaluate(p -> {
            for (String name : extension.getVariants().getNames()) {
                if (!variantNames.isEmpty() && !variantNames.contains(name)) {
                    p.getLogger().warn("[PGYER] pgyer.variants has settings for \"{}\", but this project has no such"
                            + " variant. Variants: {}", name, String.join(", ", variantNames));
                }
            }
        });
    }

    /** {@code uploadPgyer} plus the capitalized variant name. */
    public static String taskName(String variantName) {
        return "uploadPgyer" + variantName.substring(0, 1).toUpperCase(Locale.ROOT) + variantName.substring(1);
    }

    private static final class Info implements PgyerVariant {
        private final String name;
        private final String buildType;
        private final String flavorName;
        private final List<String> productFlavors;

        Info(ApplicationVariant variant) {
            name = variant.getName();
            buildType = variant.getBuildType();
            flavorName = variant.getFlavorName() == null ? "" : variant.getFlavorName();
            List<String> flavors = new ArrayList<>();
            for (Pair<String, String> flavor : variant.getProductFlavors()) {
                flavors.add(flavor.getSecond());
            }
            productFlavors = Collections.unmodifiableList(flavors);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getBuildType() {
            return buildType;
        }

        @Override
        public String getFlavorName() {
            return flavorName;
        }

        @Override
        public List<String> getProductFlavors() {
            return productFlavors;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
