package com.pgyer.upload.client;

import java.io.IOException;

/**
 * An error code returned by the PGYER API, with a hint for the codes users can fix themselves.
 */
public class PgyerApiException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public PgyerApiException(int code, String serverMessage) {
        super(format(code, serverMessage));
        this.code = code;
    }

    /** The PGYER API error code, such as {@code 1001}. */
    public int getCode() {
        return code;
    }

    private static String format(int code, String serverMessage) {
        StringBuilder sb = new StringBuilder("PGYER API error ").append(code);
        if (serverMessage != null && !serverMessage.isBlank()) {
            sb.append(": ").append(serverMessage.trim());
        }
        String hint = hint(code);
        if (hint != null) {
            sb.append(System.lineSeparator()).append(hint);
        }
        return sb.toString();
    }

    /** What the user can do about an error code, or {@code null} if there is nothing specific. */
    public static String hint(int code) {
        switch (code) {
            case 1001:
            case 1002:
                return "Check that the API key is correct. You can find it at https://www.pgyer.com/account/api";
            case 1003:
                return "Sub-accounts cannot use the API. Use the API key of the main account.";
            case 1019:
                return "Complete real-name verification before uploading: https://www.pgyer.com/account/certification";
            case 1020:
                return "Uploading is disabled for this account. Contact PGYER customer service.";
            case 1022:
                return "Your PGYER storage is full or the file is too large. Free up space or upgrade your plan.";
            default:
                return null;
        }
    }
}
