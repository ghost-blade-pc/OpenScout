package model

import "time"

type RepoSummary struct {
	Owner        string    `json:"owner"`
	Repo         string    `json:"repo"`
	FullName     string    `json:"fullName"`
	Description  string    `json:"description"`
	Language     string    `json:"language"`
	Stars        int       `json:"stars"`
	Forks        int       `json:"forks"`
	Topics       []string  `json:"topics"`
	License      string    `json:"license"`
	OpenIssues   int       `json:"openIssues"`
	UpdatedAt    time.Time `json:"updatedAt"`
	PushedAt     time.Time `json:"pushedAt"`
	ReadmeLength int       `json:"readmeLength"`
	HasExamples  bool      `json:"hasExamples"`
	HasDocker    bool      `json:"hasDocker"`
	Source       string    `json:"source"`
}

type RepoListResponse struct {
	Items []RepoSummary `json:"items"`
}

type ReadmeResponse struct {
	FullName string `json:"fullName"`
	Readme   string `json:"readme"`
	Length   int    `json:"length"`
	Source   string `json:"source"`
}

type BatchProfileRequest struct {
	Repos []string `json:"repos"`
}

type BatchProfileResponse struct {
	Items  []RepoSummary `json:"items"`
	Errors []RepoError   `json:"errors"`
}

type RepoError struct {
	FullName string `json:"fullName"`
	Message  string `json:"message"`
}

// ErrorResponse 结构化 API 错误响应，供 Go router 返回给调用方。
type ErrorResponse struct {
	Error      string `json:"error"`
	Code       string `json:"code"`
	RetryAfter int    `json:"retryAfter"`
}
