package expert

import (
	"context"
	"net"
	"sync"
	"time"
)

// A first-flow deadline covers admission and transport setup. Once connected,
// it is disarmed: streaming providers may retain their DialContext context for
// the entire connection. Parent/session cancellation and Close still apply.
type flowBudget struct {
	context.Context
	parent    context.Context
	mu        sync.Mutex
	deadline  time.Time
	armed     bool
	timer     *time.Timer
	cancel    context.CancelCauseFunc
	stopOwner func() bool
	once      sync.Once
}

func newFlowBudget(parent, owner context.Context, timeout time.Duration) *flowBudget {
	base, cancel := context.WithCancelCause(parent)
	b := &flowBudget{Context: base, parent: parent, deadline: time.Now().Add(timeout), armed: true, cancel: cancel}
	b.stopOwner = context.AfterFunc(owner, func() { cancel(context.Canceled) })
	b.timer = time.AfterFunc(timeout, func() {
		b.mu.Lock()
		defer b.mu.Unlock()
		if b.armed {
			cancel(context.DeadlineExceeded)
		}
	})
	return b
}

func (b *flowBudget) Deadline() (time.Time, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.armed {
		return b.parent.Deadline()
	}
	if parent, ok := b.parent.Deadline(); ok && parent.Before(b.deadline) {
		return parent, true
	}
	return b.deadline, true
}

func (b *flowBudget) Err() error {
	if b.Context.Err() == nil {
		return nil
	}
	if context.Cause(b.Context) == context.DeadlineExceeded {
		return context.DeadlineExceeded
	}
	return b.Context.Err()
}

func (b *flowBudget) connected() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if err := b.Err(); err != nil {
		return err
	}
	b.armed = false
	b.timer.Stop()
	return nil
}

func (b *flowBudget) close() {
	b.once.Do(func() { b.timer.Stop(); b.stopOwner(); b.cancel(context.Canceled) })
}

type budgetConn struct {
	net.Conn
	budget *flowBudget
}

func (c *budgetConn) Close() error { err := c.Conn.Close(); c.budget.close(); return err }

type budgetPacketConn struct {
	net.PacketConn
	budget *flowBudget
}

func (c *budgetPacketConn) Close() error { err := c.PacketConn.Close(); c.budget.close(); return err }
