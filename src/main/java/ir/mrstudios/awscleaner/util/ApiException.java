package ir.mrstudios.awscleaner.util;

import java.io.IOException;

public final class ApiException extends IOException {
    private final int status;
    private final String code;
    private final String retryAfter;

    public ApiException(String service, int status, String code, String retryAfter) {
        super(service + " returned HTTP " + status + (code.isEmpty() ? "" : " (" + code + ")"));
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public String code() { return code; }
    public String retryAfter() { return retryAfter; }

    public boolean missing() {
        return status == 404 || code.equals("NotFoundException");
    }

    public boolean retryable() {
        return status == 408 || status == 429 || status >= 500 || code.equals("ThrottlingException");
    }

    public boolean rejectedMutation() {
        return status >= 400 && status < 500 && status != 408 && status != 409;
    }
}
