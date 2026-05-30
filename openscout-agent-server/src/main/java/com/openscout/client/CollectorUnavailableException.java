package com.openscout.client;

/**
 * Go Collector 服务不可用（连接超时/拒绝）。
 */
public class CollectorUnavailableException extends CollectorException {

    public CollectorUnavailableException(String traceId, String message, Throwable cause) {
        super(traceId, message, cause);
    }
}
