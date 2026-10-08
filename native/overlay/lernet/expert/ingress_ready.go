package expert

import (
	"context"
	"errors"
	"sync"
	"time"
)

// Flow admission still owns the 512-flow bound while waiting. No payload is
// read, and no local TCP handshake succeeds before capture identity is proven.
type ingressReadiness struct {
	done chan struct{}
	ctx  context.Context
	once sync.Once
}

func newIngressReadiness(ctx context.Context) *ingressReadiness {
	return &ingressReadiness{done: make(chan struct{}), ctx: ctx}
}

func (r *ingressReadiness) complete() { r.once.Do(func() { close(r.done) }) }

func (r *ingressReadiness) wait(ctx context.Context) error {
	if r == nil {
		return nil
	}
	if err := r.ctx.Err(); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	deadline := time.NewTimer(5 * time.Second)
	defer deadline.Stop()
	select {
	case <-r.ctx.Done():
		return r.ctx.Err()
	case <-ctx.Done():
		return ctx.Err()
	case <-deadline.C:
		return errors.New("ingress_ready_timeout")
	case <-r.done:
		if err := r.ctx.Err(); err != nil {
			return err
		}
		return ctx.Err()
	}
}
