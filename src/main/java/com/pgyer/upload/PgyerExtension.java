package com.pgyer.upload;

import com.pgyer.upload.client.UploadOptions;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;

/**
 * The {@code pgyer { }} extension. Its settings are the defaults for every upload task in the project.
 */
public abstract class PgyerExtension implements PgyerOptions {

    /** The Gradle property that holds the API key. */
    public static final String API_KEY_PROPERTY = "pgyer.apiKey";

    /** The environment variable that holds the API key. */
    public static final String API_KEY_VARIABLE = "PGYER_API_KEY";

    private final NamedDomainObjectContainer<PgyerVariantSettings> variants;
    private Spec<? super PgyerVariant> variantFilter = Specs.satisfyAll();

    @Inject
    public PgyerExtension(ObjectFactory objects, ProviderFactory providers) {
        variants = objects.domainObjectContainer(PgyerVariantSettings.class);
        getApiKey().convention(providers.gradleProperty(API_KEY_PROPERTY)
                .orElse(providers.environmentVariable(API_KEY_VARIABLE)));
        getProcessingTimeout().convention(UploadOptions.DEFAULT_PROCESSING_TIMEOUT_SECONDS);
    }

    /** Per-variant settings and switches, by variant name. */
    public NamedDomainObjectContainer<PgyerVariantSettings> getVariants() {
        return variants;
    }

    /** Configures the per-variant settings. */
    public void variants(Action<? super NamedDomainObjectContainer<PgyerVariantSettings>> action) {
        action.execute(variants);
    }

    /**
     * Creates {@code uploadPgyer<Variant>} tasks only for the Android variants that match, for example
     * {@code variantFilter { it.buildType == "release" }}. All variants match by default.
     */
    public void variantFilter(Spec<? super PgyerVariant> filter) {
        this.variantFilter = filter;
    }

    Spec<? super PgyerVariant> getVariantFilter() {
        return variantFilter;
    }
}
