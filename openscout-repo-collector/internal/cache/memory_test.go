package cache

import (
	"testing"
	"time"

	"github.com/LiPeicheng/openscout-repo-collector/internal/model"
)

func TestMemoryCacheExpires(t *testing.T) {
	cache := NewMemoryCache(10 * time.Millisecond)
	cache.SetRepo("repo", model.RepoSummary{FullName: "owner/repo"})

	if _, ok := cache.GetRepo("repo"); !ok {
		t.Fatal("expected cached repo")
	}
	time.Sleep(20 * time.Millisecond)
	if _, ok := cache.GetRepo("repo"); ok {
		t.Fatal("expected cached repo to expire")
	}
}
