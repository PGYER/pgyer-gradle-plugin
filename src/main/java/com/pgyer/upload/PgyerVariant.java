package com.pgyer.upload;

import java.util.List;

/**
 * An Android application variant, as passed to {@link PgyerExtension#variantFilter}.
 */
public interface PgyerVariant {

    /** The variant name, such as {@code release} or {@code freeRelease}. */
    String getName();

    /** The build type, such as {@code release}, or {@code null}. */
    String getBuildType();

    /** The combined flavor name, such as {@code free}, or {@code ""} without flavors. */
    String getFlavorName();

    /** The product flavor names, in dimension order. */
    List<String> getProductFlavors();
}
