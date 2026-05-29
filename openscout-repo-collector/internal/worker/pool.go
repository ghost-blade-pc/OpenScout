package worker

import (
	"context"
	"sync"

	"github.com/LiPeicheng/openscout-repo-collector/internal/model"
)

type Pool struct {
	concurrency int
}

func NewPool(concurrency int) *Pool {
	if concurrency <= 0 {
		concurrency = 4
	}
	return &Pool{concurrency: concurrency}
}

func (p *Pool) Run(ctx context.Context, repos []string, fetch func(context.Context, string) (model.RepoSummary, error)) model.BatchProfileResponse {
	jobs := make(chan string)
	var wg sync.WaitGroup
	var mu sync.Mutex
	response := model.BatchProfileResponse{
		Items:  make([]model.RepoSummary, 0, len(repos)),
		Errors: make([]model.RepoError, 0),
	}

	for i := 0; i < p.concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for fullName := range jobs {
				item, err := fetch(ctx, fullName)
				mu.Lock()
				if err != nil {
					response.Errors = append(response.Errors, model.RepoError{FullName: fullName, Message: err.Error()})
				} else {
					response.Items = append(response.Items, item)
				}
				mu.Unlock()
			}
		}()
	}

	for _, fullName := range repos {
		select {
		case <-ctx.Done():
			mu.Lock()
			response.Errors = append(response.Errors, model.RepoError{FullName: fullName, Message: ctx.Err().Error()})
			mu.Unlock()
		case jobs <- fullName:
		}
	}
	close(jobs)
	wg.Wait()
	return response
}
