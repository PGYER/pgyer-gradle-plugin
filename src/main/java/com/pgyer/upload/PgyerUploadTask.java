package com.pgyer.upload;

import java.io.File;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Uploads any {@code .apk}, {@code .ipa} or {@code .hap} file to PGYER. Needs no Android Gradle Plugin,
 * so it works for prebuilt files, iOS builds (for example from Kotlin Multiplatform) and HarmonyOS apps.
 *
 * <pre>{@code
 * tasks.register<com.pgyer.upload.PgyerUploadTask>("uploadIpa") {
 *     file = layout.buildDirectory.file("ios/MyApp.ipa")
 *     updateDescription = "Nightly build"
 * }
 * }</pre>
 *
 * <p>Settings not set on the task come from the {@code pgyer { }} extension.
 */
@UntrackedTask(because = "Every run publishes a new build to PGYER")
public abstract class PgyerUploadTask extends AbstractPgyerUploadTask {

    /** The {@code .apk}, {@code .ipa} or {@code .hap} file to upload. */
    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getFile();

    @Override
    protected File resolveUploadFile() {
        return getFile().get().getAsFile();
    }
}
