package com.pgyer.upload.client;

/**
 * Where {@link PgyerClient} reports progress. Messages never contain the API key, the install
 * password or the storage credentials.
 */
public interface PgyerLog {

    /** Progress worth showing by default. */
    void lifecycle(String message);

    /** Details shown with {@code --info}. */
    void info(String message);

    /** Something the user should look at, but that does not stop the upload. */
    void warn(String message);
}
