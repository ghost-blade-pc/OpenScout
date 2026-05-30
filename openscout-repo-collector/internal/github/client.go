package github

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/LiPeicheng/openscout-repo-collector/internal/limiter"
	"github.com/LiPeicheng/openscout-repo-collector/internal/model"
)

const baseURL = "https://api.github.com"

type Client struct {
	httpClient *http.Client
	token      string
	limiter    *limiter.Limiter
	logger     *slog.Logger
}

func NewClient(httpClient *http.Client, token string, limiter *limiter.Limiter, logger *slog.Logger) *Client {
	return &Client{
		httpClient: httpClient,
		token:      token,
		limiter:    limiter,
		logger:     logger,
	}
}

func (c *Client) SearchRepos(ctx context.Context, keyword string, limit int) ([]model.RepoSummary, error) {
	if strings.TrimSpace(keyword) == "" {
		keyword = "spring ai"
	}
	endpoint := baseURL + "/search/repositories?q=" + url.QueryEscape(keyword) + "&sort=stars&order=desc&per_page=" + fmt.Sprintf("%d", limit)
	var payload searchResponse
	if err := c.getJSON(ctx, endpoint, &payload); err != nil {
		return nil, err
	}
	items := make([]model.RepoSummary, 0, len(payload.Items))
	for _, item := range payload.Items {
		items = append(items, item.toRepoSummary())
	}
	return items, nil
}

func (c *Client) Profile(ctx context.Context, owner, repo string) (model.RepoSummary, error) {
	endpoint := fmt.Sprintf("%s/repos/%s/%s", baseURL, url.PathEscape(owner), url.PathEscape(repo))
	var payload repoResponse
	if err := c.getJSON(ctx, endpoint, &payload); err != nil {
		return model.RepoSummary{}, err
	}
	return payload.toRepoSummary(), nil
}

func (c *Client) Readme(ctx context.Context, owner, repo string) (model.ReadmeResponse, error) {
	endpoint := fmt.Sprintf("%s/repos/%s/%s/readme", baseURL, url.PathEscape(owner), url.PathEscape(repo))
	var payload readmeResponse
	if err := c.getJSON(ctx, endpoint, &payload); err != nil {
		return model.ReadmeResponse{}, err
	}
	content := payload.Content
	if payload.Encoding == "base64" {
		decoded, err := base64.StdEncoding.DecodeString(strings.ReplaceAll(payload.Content, "\n", ""))
		if err == nil {
			content = string(decoded)
		}
	}
	if len(content) > 8000 {
		content = content[:8000]
	}
	return model.ReadmeResponse{
		FullName: owner + "/" + repo,
		Readme:   content,
		Length:   len(content),
		Source:   "github",
	}, nil
}

func (c *Client) getJSON(ctx context.Context, endpoint string, target any) error {
	if err := c.limiter.Wait(ctx); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	req.Header.Set("User-Agent", "OpenScout-Agent")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusForbidden ||
		resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return newGitHubError(resp.StatusCode, string(body), resp.Header.Get("Retry-After"))
	}
	return json.NewDecoder(resp.Body).Decode(target)
}

type searchResponse struct {
	Items []repoResponse `json:"items"`
}

type repoResponse struct {
	Name        string    `json:"name"`
	FullName    string    `json:"full_name"`
	Owner       ownerInfo `json:"owner"`
	Description string    `json:"description"`
	Language    string    `json:"language"`
	Stars       int       `json:"stargazers_count"`
	Forks       int       `json:"forks_count"`
	Topics      []string  `json:"topics"`
	License     *license  `json:"license"`
	OpenIssues  int       `json:"open_issues_count"`
	UpdatedAt   string    `json:"updated_at"`
	PushedAt    string    `json:"pushed_at"`
}

type ownerInfo struct {
	Login string `json:"login"`
}

type license struct {
	SPDXID string `json:"spdx_id"`
}

type readmeResponse struct {
	Content  string `json:"content"`
	Encoding string `json:"encoding"`
}

func (r repoResponse) toRepoSummary() model.RepoSummary {
	licenseID := ""
	if r.License != nil {
		licenseID = r.License.SPDXID
	}
	return model.RepoSummary{
		Owner:       r.Owner.Login,
		Repo:        r.Name,
		FullName:    r.FullName,
		Description: r.Description,
		Language:    r.Language,
		Stars:       r.Stars,
		Forks:       r.Forks,
		Topics:      r.Topics,
		License:     licenseID,
		OpenIssues:  r.OpenIssues,
		UpdatedAt:   parseTime(r.UpdatedAt),
		PushedAt:    parseTime(r.PushedAt),
		Source:      "github",
	}
}

func parseTime(value string) time.Time {
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		return time.Time{}
	}
	return parsed
}
