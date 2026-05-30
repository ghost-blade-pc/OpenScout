package com.openscout.client;

/**
 * Collector 调用异常的基类。
 */
public class CollectorException extends RuntimeException {

    private final String traceId;

    public CollectorException(String traceId, String message) {
        super(message);
        this.traceId = traceId;
    }

    public CollectorException(String traceId, String message, Throwable cause) {
        super(message, cause);
        this.traceId = traceId;
    }

    public String getTraceId() {
        return traceId;
    }
}
