package main

import (
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/LiPeicheng/openscout-repo-collector/internal/api"
	"github.com/LiPeicheng/openscout-repo-collector/internal/cache"
	"github.com/LiPeicheng/openscout-repo-collector/internal/github"
	"github.com/LiPeicheng/openscout-repo-collector/internal/limiter"
	"github.com/LiPeicheng/openscout-repo-collector/internal/service"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	port := env("PORT", "8081")
	mode := env("OPSCOUT_COLLECTOR_MODE", "mock")

	httpClient := &http.Client{Timeout: 8 * time.Second}
	memCache := cache.NewMemoryCache(10 * time.Minute)
	rateLimiter := limiter.New(2, 4)
	githubClient := github.NewClient(httpClient, env("GITHUB_TOKEN", ""), rateLimiter, logger)
	repoService := service.NewRepoService(mode, githubClient, memCache, logger)

	router := api.NewRouter(repoService, logger)
	logger.Info("starting openscout repo collector", "port", port, "mode", mode)
	if err := router.Run(":" + port); err != nil {
		logger.Error("collector server stopped", "error", err)
		os.Exit(1)
	}
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
