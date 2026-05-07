package com.oriontv.legacy.api;

public class ApiException extends Exception {
    private final int statusCode;

    public ApiException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
