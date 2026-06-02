package cache

import (
	"sync"
	"time"

	"github.com/LiPeicheng/openscout-repo-collector/internal/model"
)

const maxEntries = 10000

type MemoryCache struct {
	ttl     time.Duration
	mu      sync.RWMutex
	repos   map[string]cacheItem[[]model.RepoSummary]
	repo    map[string]cacheItem[model.RepoSummary]
	readmes map[string]cacheItem[model.ReadmeResponse]
	done    chan struct{}
}

type cacheItem[T any] struct {
	value     T
	expiresAt time.Time
}

func NewMemoryCache(ttl time.Duration) *MemoryCache {
	c := &MemoryCache{
		ttl:     ttl,
		repos:   make(map[string]cacheItem[[]model.RepoSummary]),
		repo:    make(map[string]cacheItem[model.RepoSummary]),
		readmes: make(map[string]cacheItem[model.ReadmeResponse]),
		done:    make(chan struct{}),
	}
	go c.evictionLoop()
	return c
}

func (c *MemoryCache) Stop() {
	close(c.done)
}

func (c *MemoryCache) evictionLoop() {
	if c.ttl <= 0 {
		return
	}
	interval := c.ttl / 2
	if interval < time.Minute {
		interval = time.Minute
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			c.evictExpired()
		case <-c.done:
			return
		}
	}
}

func (c *MemoryCache) evictExpired() {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for k, v := range c.repos {
		if now.After(v.expiresAt) {
			delete(c.repos, k)
		}
	}
	for k, v := range c.repo {
		if now.After(v.expiresAt) {
			delete(c.repo, k)
		}
	}
	for k, v := range c.readmes {
		if now.After(v.expiresAt) {
			delete(c.readmes, k)
		}
	}
}

func (c *MemoryCache) GetRepos(key string) ([]model.RepoSummary, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	item, ok := c.repos[key]
	if !ok || time.Now().After(item.expiresAt) {
		return nil, false
	}
	return item.value, true
}

func (c *MemoryCache) SetRepos(key string, value []model.RepoSummary) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.repos[key] = cacheItem[[]model.RepoSummary]{value: value, expiresAt: time.Now().Add(c.ttl)}
}

func (c *MemoryCache) GetRepo(key string) (model.RepoSummary, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	item, ok := c.repo[key]
	if !ok || time.Now().After(item.expiresAt) {
		return model.RepoSummary{}, false
	}
	return item.value, true
}

func (c *MemoryCache) SetRepo(key string, value model.RepoSummary) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.repo) >= maxEntries {
		return
	}
	c.repo[key] = cacheItem[model.RepoSummary]{value: value, expiresAt: time.Now().Add(c.ttl)}
}

func (c *MemoryCache) GetReadme(key string) (model.ReadmeResponse, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	item, ok := c.readmes[key]
	if !ok || time.Now().After(item.expiresAt) {
		return model.ReadmeResponse{}, false
	}
	return item.value, true
}

func (c *MemoryCache) SetReadme(key string, value model.ReadmeResponse) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.readmes) >= maxEntries {
		return
	}
	c.readmes[key] = cacheItem[model.ReadmeResponse]{value: value, expiresAt: time.Now().Add(c.ttl)}
}
