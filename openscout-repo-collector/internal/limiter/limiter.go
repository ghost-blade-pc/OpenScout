package limiter

import (
	"context"

	"golang.org/x/time/rate"
)

type Limiter struct {
	limiter *rate.Limiter
}

func New(requestsPerSecond rate.Limit, burst int) *Limiter {
	return &Limiter{limiter: rate.NewLimiter(requestsPerSecond, burst)}
}

func (l *Limiter) Wait(ctx context.Context) error {
	return l.limiter.Wait(ctx)
}
