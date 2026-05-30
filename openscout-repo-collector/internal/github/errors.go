package github

import (
	"fmt"
	"strconv"
	"time"
)

// GitHubError 结构化 GitHub API 错误，区分 403/429/404/其他非 2xx。
type GitHubError struct {
	StatusCode int
	Code       string // RATE_LIMITED, FORBIDDEN, NOT_FOUND, API_ERROR
	Message    string
	RetryAfter int // 秒，仅 RATE_LIMITED 时有效
}

func (e *GitHubError) Error() string {
	if e.RetryAfter > 0 {
		return fmt.Sprintf("github %s (status=%d): %s (retry after %ds)", e.Code, e.StatusCode, e.Message, e.RetryAfter)
	}
	return fmt.Sprintf("github %s (status=%d): %s", e.Code, e.StatusCode, e.Message)
}

// IsRetryable 表示该错误可以通过等待后重试恢复。
func (e *GitHubError) IsRetryable() bool {
	return e.Code == "RATE_LIMITED"
}

// newGitHubError 根据 HTTP 状态码和响应头构造 GitHubError。
func newGitHubError(statusCode int, body string, headerRetryAfter string) *GitHubError {
	ge := &GitHubError{
		StatusCode: statusCode,
		Message:    body,
	}
	switch {
	case statusCode == 404:
		ge.Code = "NOT_FOUND"
	case statusCode == 403:
		ge.Code = "FORBIDDEN"
	case statusCode == 429:
		ge.Code = "RATE_LIMITED"
		ge.RetryAfter = parseRetryAfter(headerRetryAfter)
	default:
		ge.Code = "API_ERROR"
	}
	return ge
}

// parseRetryAfter 尝试解析 Retry-After 头：先按秒数，再按 HTTP 日期。
func parseRetryAfter(value string) int {
	if value == "" {
		return 0
	}
	if seconds, err := strconv.Atoi(value); err == nil {
		return seconds
	}
	if t, err := time.Parse(time.RFC1123, value); err == nil {
		delay := time.Until(t)
		if delay > 0 {
			return int(delay.Seconds())
		}
	}
	return 0
}
