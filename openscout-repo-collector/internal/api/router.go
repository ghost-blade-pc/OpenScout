package api

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/LiPeicheng/openscout-repo-collector/internal/github"
	"github.com/LiPeicheng/openscout-repo-collector/internal/model"
	"github.com/LiPeicheng/openscout-repo-collector/internal/service"
	"github.com/gin-gonic/gin"
)

func NewRouter(repoService *service.RepoService, logger *slog.Logger, apiKey string, apiKeyHeader string) *gin.Engine {
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(requestLogger(logger))

	// API Key 保护：仅当配置了 key 时启用；/health 始终豁免
	if apiKey != "" {
		router.Use(apiKeyMiddleware(apiKey, apiKeyHeader, logger))
	}

	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "UP"})
	})

	repos := router.Group("/api/repos")
	{
		repos.GET("/mock", func(c *gin.Context) {
			keyword := c.Query("keyword")
			c.JSON(http.StatusOK, service.RepoListResponse{Items: repoService.MockRepos(keyword)})
		})
		repos.GET("/search", func(c *gin.Context) {
			limit, _ := strconv.Atoi(c.DefaultQuery("limit", "10"))
			items, err := repoService.Search(c.Request.Context(), c.Query("keyword"), limit, c.Query("mode"))
			if err != nil {
				c.JSON(http.StatusBadGateway, toErrorResponse(err))
				return
			}
			c.JSON(http.StatusOK, service.RepoListResponse{Items: items})
		})
		repos.GET("/:owner/:repo/profile", func(c *gin.Context) {
			item, err := repoService.Profile(c.Request.Context(), c.Param("owner"), c.Param("repo"), c.Query("mode"))
			if err != nil {
				c.JSON(http.StatusBadGateway, toErrorResponse(err))
				return
			}
			c.JSON(http.StatusOK, item)
		})
		repos.GET("/:owner/:repo/readme", func(c *gin.Context) {
			item, err := repoService.Readme(c.Request.Context(), c.Param("owner"), c.Param("repo"), c.Query("mode"))
			if err != nil {
				c.JSON(http.StatusBadGateway, toErrorResponse(err))
				return
			}
			c.JSON(http.StatusOK, item)
		})
		repos.POST("/batch-profile", func(c *gin.Context) {
			var request service.BatchProfileRequest
			if err := c.ShouldBindJSON(&request); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"message": "invalid request body"})
				return
			}
			response := repoService.BatchProfile(c.Request.Context(), request.Repos, c.Query("mode"))
			c.JSON(http.StatusOK, response)
		})
	}

	return router
}

// toErrorResponse 将 error 转为结构化 ErrorResponse。
// *github.GitHubError 直接映射 Code/RetryAfter；其他 error Code 设为 "INTERNAL"。
func toErrorResponse(err error) model.ErrorResponse {
	var ghErr *github.GitHubError
	if errors.As(err, &ghErr) {
		return model.ErrorResponse{
			Error:      ghErr.Message,
			Code:       ghErr.Code,
			RetryAfter: ghErr.RetryAfter,
		}
	}
	return model.ErrorResponse{
		Error: err.Error(),
		Code:  "INTERNAL",
	}
}

func requestLogger(logger *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		logger.Info("request completed",
			"method", c.Request.Method,
			"path", c.Request.URL.Path,
			"status", c.Writer.Status(),
			"latency_ms", time.Since(start).Milliseconds(),
		)
	}
}

// apiKeyMiddleware 最小 API Key 保护。
// 当 apiKey 非空时启用，/health 路径豁免。
func apiKeyMiddleware(apiKey string, headerName string, logger *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		// 豁免健康检查
		if c.Request.URL.Path == "/health" {
			c.Next()
			return
		}

		actualKey := c.GetHeader(headerName)
		if actualKey == "" {
			logger.Warn("API Key missing", "path", c.Request.URL.Path)
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"error": "API key required",
				"code":  "UNAUTHORIZED",
			})
			return
		}

		if actualKey != apiKey {
			logger.Warn("API Key invalid", "path", c.Request.URL.Path)
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"error": "API key invalid",
				"code":  "UNAUTHORIZED",
			})
			return
		}

		c.Next()
	}
}
