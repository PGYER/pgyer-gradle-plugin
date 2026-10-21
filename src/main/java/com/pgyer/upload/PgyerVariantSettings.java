package com.pgyer.upload;

import javax.inject.Inject;
import org.gradle.api.Named;
import org.gradle.api.provider.Property;

/**
 * Settings for one Android variant, declared with {@code pgyer { variants { create("release") { ... } } }}.
 * Anything set here overrides the extension's setting for that variant.
 */
public abstract class PgyerVariantSettings implements Named, PgyerOptions {

    private final String name;

    @Inject
    public PgyerVariantSettings(String name) {
        this.name = name;
    }

    /** The variant name, such as {@code release} or {@code freeRelease}. */
    @Override
    public String getName() {
        return name;
    }

    /** {@code false} skips this variant: no {@code uploadPgyer<Variant>} task is created. Defaults to {@code true}. */
    public abstract Property<Boolean> getEnabled();
}
