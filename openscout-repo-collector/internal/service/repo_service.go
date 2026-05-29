package service

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/LiPeicheng/openscout-repo-collector/internal/cache"
	"github.com/LiPeicheng/openscout-repo-collector/internal/github"
	"github.com/LiPeicheng/openscout-repo-collector/internal/worker"
)

type RepoService struct {
	mode         string
	githubClient *github.Client
	cache        *cache.MemoryCache
	logger       *slog.Logger
}

func NewRepoService(mode string, githubClient *github.Client, cache *cache.MemoryCache, logger *slog.Logger) *RepoService {
	if mode == "" {
		mode = "mock"
	}
	return &RepoService{
		mode:         mode,
		githubClient: githubClient,
		cache:        cache,
		logger:       logger,
	}
}

func (s *RepoService) MockRepos(keyword string) []RepoSummary {
	now := time.Now().UTC()
	return []RepoSummary{
		{
			Owner:        "spring-projects",
			Repo:         "spring-ai",
			FullName:     "spring-projects/spring-ai",
			Description:  "Spring AI application framework for building AI-powered Java applications.",
			Language:     "Java",
			Stars:        12000,
			Forks:        2100,
			Topics:       []string{"spring", "ai", "llm", "agents"},
			License:      "Apache-2.0",
			OpenIssues:   120,
			UpdatedAt:    now.AddDate(0, 0, -10),
			PushedAt:     now.AddDate(0, 0, -5),
			ReadmeLength: 5200,
			HasExamples:  true,
			HasDocker:    true,
			Source:       "mock",
		},
		{
			Owner:        "langchain4j",
			Repo:         "langchain4j",
			FullName:     "langchain4j/langchain4j",
			Description:  "Java library for building applications with LLMs.",
			Language:     "Java",
			Stars:        6500,
			Forks:        1200,
			Topics:       []string{"java", "llm", "rag", "tools"},
			License:      "Apache-2.0",
			OpenIssues:   180,
			UpdatedAt:    now.AddDate(0, 0, -8),
			PushedAt:     now.AddDate(0, 0, -2),
			ReadmeLength: 4300,
			HasExamples:  true,
			HasDocker:    false,
			Source:       "mock",
		},
		{
			Owner:        "gin-gonic",
			Repo:         "gin",
			FullName:     "gin-gonic/gin",
			Description:  "HTTP web framework written in Go, useful for learning Go services and middleware.",
			Language:     "Go",
			Stars:        82000,
			Forks:        8200,
			Topics:       []string{"go", "gin", "web", "http"},
			License:      "MIT",
			OpenIssues:   900,
			UpdatedAt:    now.AddDate(0, 0, -3),
			PushedAt:     now.AddDate(0, 0, -1),
			ReadmeLength: 3100,
			HasExamples:  true,
			HasDocker:    false,
			Source:       "mock",
		},
	}
}

func (s *RepoService) Search(ctx context.Context, keyword string, limit int, mode string) ([]RepoSummary, error) {
	if limit <= 0 || limit > 20 {
		limit = 10
	}
	if s.useMock(mode) {
		items := s.MockRepos(keyword)
		if len(items) > limit {
			return items[:limit], nil
		}
		return items, nil
	}
	key := "search:" + keyword + fmt.Sprintf(":%d", limit)
	if cached, ok := s.cache.GetRepos(key); ok {
		return cached, nil
	}
	items, err := s.githubClient.SearchRepos(ctx, keyword, limit)
	if err != nil {
		return nil, err
	}
	s.cache.SetRepos(key, items)
	return items, nil
}

func (s *RepoService) Profile(ctx context.Context, owner, repo, mode string) (RepoSummary, error) {
	if s.useMock(mode) {
		fullName := owner + "/" + repo
		for _, item := range s.MockRepos(fullName) {
			if strings.EqualFold(item.FullName, fullName) {
				return item, nil
			}
		}
		return RepoSummary{}, fmt.Errorf("mock repo not found: %s", fullName)
	}
	key := "profile:" + owner + "/" + repo
	if cached, ok := s.cache.GetRepo(key); ok {
		return cached, nil
	}
	item, err := s.githubClient.Profile(ctx, owner, repo)
	if err != nil {
		return RepoSummary{}, err
	}
	s.cache.SetRepo(key, item)
	return item, nil
}

func (s *RepoService) Readme(ctx context.Context, owner, repo, mode string) (ReadmeResponse, error) {
	fullName := owner + "/" + repo
	if s.useMock(mode) {
		return ReadmeResponse{
			FullName: fullName,
			Readme:   "# " + fullName + "\n\nMock README for OpenScout local demos. It contains quickstart, examples, and learning notes.",
			Length:   96,
			Source:   "mock",
		}, nil
	}
	key := "readme:" + fullName
	if cached, ok := s.cache.GetReadme(key); ok {
		return cached, nil
	}
	item, err := s.githubClient.Readme(ctx, owner, repo)
	if err != nil {
		return ReadmeResponse{}, err
	}
	s.cache.SetReadme(key, item)
	return item, nil
}

func (s *RepoService) BatchProfile(ctx context.Context, repos []string, mode string) BatchProfileResponse {
	if len(repos) == 0 {
		return BatchProfileResponse{}
	}
	pool := worker.NewPool(4)
	return pool.Run(ctx, repos, func(ctx context.Context, fullName string) (RepoSummary, error) {
		owner, repo, ok := splitFullName(fullName)
		if !ok {
			return RepoSummary{}, fmt.Errorf("invalid repo full name")
		}
		return s.Profile(ctx, owner, repo, mode)
	})
}

func (s *RepoService) useMock(mode string) bool {
	if mode != "" {
		return strings.EqualFold(mode, "mock")
	}
	return strings.EqualFold(s.mode, "mock")
}

func splitFullName(fullName string) (string, string, bool) {
	parts := strings.Split(fullName, "/")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return "", "", false
	}
	return parts[0], parts[1], true
}
