package com.openscout.client;

/**
 * GitHub API 通用错误（403 FORBIDDEN / 404 NOT_FOUND / 其他非 2xx）。
 */
public class GitHubApiException extends CollectorException {

    private final int httpStatus;
    private final String errorCode;

    public GitHubApiException(String traceId, String message, int httpStatus, String errorCode) {
        super(traceId, message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
