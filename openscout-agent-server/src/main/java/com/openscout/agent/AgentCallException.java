package com.openscout.agent;

public class AgentCallException extends RuntimeException {

    private final String traceId;

    public AgentCallException(String traceId, String message, Throwable cause) {
        super(message, cause);
        this.traceId = traceId;
    }

    public String getTraceId() {
        return traceId;
    }
}
