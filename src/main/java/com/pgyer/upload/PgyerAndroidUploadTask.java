package com.pgyer.upload;

import com.android.build.api.variant.BuiltArtifact;
import com.android.build.api.variant.BuiltArtifacts;
import com.android.build.api.variant.BuiltArtifactsLoader;
import com.android.build.api.variant.VariantOutputConfiguration;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Uploads the APK of an Android application variant. The plugin creates one per variant, named
 * {@code uploadPgyer<Variant>}, such as {@code uploadPgyerRelease}. Running it builds the APK first.
 *
 * <p>Needs the Android Gradle Plugin 8 or later.
 */
@UntrackedTask(because = "Every run publishes a new build to PGYER")
public abstract class PgyerAndroidUploadTask extends AbstractPgyerUploadTask {

    /** The variant's APK directory ({@code SingleArtifact.APK}). */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getApkDirectory();

    /** Reads the APK directory's {@code output-metadata.json}. */
    @Internal
    public abstract Property<BuiltArtifactsLoader> getBuiltArtifactsLoader();

    /** The variant name, such as {@code release}. */
    @Input
    public abstract Property<String> getVariantName();

    @Override
    protected File resolveUploadFile() {
        BuiltArtifacts artifacts = getBuiltArtifactsLoader().get().load(getApkDirectory().get());
        String variant = getVariantName().get();
        if (artifacts == null || artifacts.getElements().isEmpty()) {
            throw new InvalidUserDataException(
                    "The " + variant + " variant produced no APK in " + getApkDirectory().get().getAsFile());
        }
        Collection<BuiltArtifact> elements = artifacts.getElements();
        if (elements.size() == 1) {
            return new File(elements.iterator().next().getOutputFile());
        }
        List<String> names = new ArrayList<>();
        for (BuiltArtifact element : elements) {
            if (element.getOutputType() == VariantOutputConfiguration.OutputType.UNIVERSAL) {
                return new File(element.getOutputFile());
            }
            names.add(new File(element.getOutputFile()).getName());
        }
        throw new InvalidUserDataException("The " + variant + " variant produces " + elements.size()
                + " APKs (" + String.join(", ", names) + "). Turn on a universal APK"
                + " (splits { abi { isUniversalApk = true } }) or upload one of them with a PgyerUploadTask.");
    }
}
