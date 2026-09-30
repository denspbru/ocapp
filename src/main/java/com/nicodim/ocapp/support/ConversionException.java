package com.nicodim.ocapp.support;

import org.springframework.http.HttpStatus;

public class ConversionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ConversionException(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
    public ConversionException(HttpStatus status, String code, String message, Throwable cause) { super(message, cause); this.status = status; this.code = code; }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
