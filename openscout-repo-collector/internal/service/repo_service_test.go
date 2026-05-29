package service

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/LiPeicheng/openscout-repo-collector/internal/cache"
)

func TestMockRepos(t *testing.T) {
	svc := NewRepoService("mock", nil, cache.NewMemoryCache(time.Minute), slog.Default())

	items := svc.MockRepos("spring ai")

	if len(items) == 0 {
		t.Fatal("expected mock repos")
	}
	if items[0].FullName == "" {
		t.Fatal("expected fullName")
	}
}

func TestBatchProfileAllowsPartialFailure(t *testing.T) {
	svc := NewRepoService("mock", nil, cache.NewMemoryCache(time.Minute), slog.Default())

	response := svc.BatchProfile(context.Background(), []string{"spring-projects/spring-ai", "bad"}, "mock")

	if len(response.Items) != 1 {
		t.Fatalf("expected one successful item, got %d", len(response.Items))
	}
	if len(response.Errors) != 1 {
		t.Fatalf("expected one error, got %d", len(response.Errors))
	}
}
