package api

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/LiPeicheng/openscout-repo-collector/internal/service"
	"github.com/gin-gonic/gin"
)

// setupTestRouter mirrors NewRouter's middleware registration order.
// When NewRouter changes its middleware setup, this helper must be updated too.
func setupTestRouter(apiKey string, apiKeyHeader string) *gin.Engine {
	gin.SetMode(gin.TestMode)
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	router := gin.New()
	router.Use(gin.Recovery())

	if apiKey != "" {
		router.Use(apiKeyMiddleware(apiKey, apiKeyHeader, logger))
	}

	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "UP"})
	})

	router.GET("/api/repos/mock", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"items": []string{}})
	})

	return router
}

// TestNewRouter_WiresMiddleware verifies that NewRouter successfully constructs
// with the middleware wired, acting as a canary for middleware registration regressions.
func TestNewRouter_WiresMiddleware(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	svc := service.NewRepoService("mock", nil, nil, logger, 1)

	// Should not panic
	router := NewRouter(svc, logger, "secret", "X-OpenScout-Api-Key")
	if router == nil {
		t.Fatal("NewRouter returned nil")
	}

	// Without key → 401
	req := httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 without key, got %d", w.Code)
	}

	// With correct key → 200
	req = httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	req.Header.Set("X-OpenScout-Api-Key", "secret")
	w = httptest.NewRecorder()
	router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 with valid key, got %d", w.Code)
	}

	// Health exempt
	req = httptest.NewRequest(http.MethodGet, "/health", nil)
	w = httptest.NewRecorder()
	router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for health, got %d", w.Code)
	}

	// Disabled case — no middleware
	router2 := NewRouter(svc, logger, "", "X-OpenScout-Api-Key")
	req = httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	w = httptest.NewRecorder()
	router2.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 with disabled API key, got %d", w.Code)
	}
}

func TestApiKeyMiddleware_Disabled(t *testing.T) {
	router := setupTestRouter("", "X-OpenScout-Api-Key")

	req := httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200 when API key disabled, got %d", w.Code)
	}
}

func TestApiKeyMiddleware_MissingKey(t *testing.T) {
	router := setupTestRouter("secret", "X-OpenScout-Api-Key")

	req := httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 when key missing, got %d", w.Code)
	}
	body := w.Body.String()
	if !strings.Contains(body, "UNAUTHORIZED") {
		t.Errorf("expected UNAUTHORIZED in body, got %s", body)
	}
}

func TestApiKeyMiddleware_InvalidKey(t *testing.T) {
	router := setupTestRouter("secret", "X-OpenScout-Api-Key")

	req := httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	req.Header.Set("X-OpenScout-Api-Key", "wrong")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 when key invalid, got %d", w.Code)
	}
	body := w.Body.String()
	if !strings.Contains(body, "UNAUTHORIZED") {
		t.Errorf("expected UNAUTHORIZED in body, got %s", body)
	}
}

func TestApiKeyMiddleware_ValidKey(t *testing.T) {
	router := setupTestRouter("secret", "X-OpenScout-Api-Key")

	req := httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	req.Header.Set("X-OpenScout-Api-Key", "secret")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200 when key valid, got %d", w.Code)
	}
}

func TestApiKeyMiddleware_CustomHeader(t *testing.T) {
	router := setupTestRouter("secret", "X-Custom-Key")

	// wrong header name — should fail
	req := httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	req.Header.Set("X-OpenScout-Api-Key", "secret")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 with wrong header name, got %d", w.Code)
	}

	// correct custom header — should pass
	req = httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	req.Header.Set("X-Custom-Key", "secret")
	w = httptest.NewRecorder()
	router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 with custom header, got %d", w.Code)
	}
}

func TestApiKeyMiddleware_HealthExempt(t *testing.T) {
	router := setupTestRouter("secret", "X-OpenScout-Api-Key")

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for health (exempt), got %d", w.Code)
	}
}

func TestApiKeyMiddleware_NoKeyLeak(t *testing.T) {
	router := setupTestRouter("secret", "X-OpenScout-Api-Key")

	req := httptest.NewRequest(http.MethodGet, "/api/repos/mock", nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	body := w.Body.String()
	if strings.Contains(body, "secret") {
		t.Errorf("error response must not leak API key, got %s", body)
	}
}
