package main

import (
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/LiPeicheng/openscout-repo-collector/internal/api"
	"github.com/LiPeicheng/openscout-repo-collector/internal/cache"
	"github.com/LiPeicheng/openscout-repo-collector/internal/github"
	"github.com/LiPeicheng/openscout-repo-collector/internal/limiter"
	"github.com/LiPeicheng/openscout-repo-collector/internal/service"

	"golang.org/x/time/rate"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	port := env("PORT", "8081")
	mode := env("OPSCOUT_COLLECTOR_MODE", "mock")
	apiKey := env("OPSCOUT_COLLECTOR_API_KEY", "")
	apiKeyHeader := env("OPSCOUT_API_KEY_HEADER", "X-OpenScout-Api-Key")

	rateLimitRPS := envFloat("OPSCOUT_RATE_LIMIT_RPS", 2, logger)
	rateLimitBurst := envInt("OPSCOUT_RATE_LIMIT_BURST", 4, logger)
	cacheTTLMinutes := envInt("OPSCOUT_CACHE_TTL_MINUTES", 10, logger)
	workerConcurrency := envInt("OPSCOUT_WORKER_CONCURRENCY", 4, logger)
	httpTimeoutSeconds := envInt("OPSCOUT_HTTP_TIMEOUT_SECONDS", 8, logger)

	httpClient := &http.Client{Timeout: time.Duration(httpTimeoutSeconds) * time.Second}
	memCache := cache.NewMemoryCache(time.Duration(cacheTTLMinutes) * time.Minute)
	rateLimiter := limiter.New(rate.Limit(rateLimitRPS), rateLimitBurst)
	githubClient := github.NewClient(httpClient, env("GITHUB_TOKEN", ""), rateLimiter, logger)
	repoService := service.NewRepoService(mode, githubClient, memCache, logger, workerConcurrency)

	router := api.NewRouter(repoService, logger, apiKey, apiKeyHeader)
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

func envInt(key string, fallback int, logger *slog.Logger) int {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(raw)
	if err != nil {
		logger.Warn("failed to parse env var, using fallback", "key", key, "raw", raw, "fallback", fallback)
		return fallback
	}
	return parsed
}

func envFloat(key string, fallback float64, logger *slog.Logger) float64 {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}
	parsed, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		logger.Warn("failed to parse env var, using fallback", "key", key, "raw", raw, "fallback", fallback)
		return fallback
	}
	return parsed
}
