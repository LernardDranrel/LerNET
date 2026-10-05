package box

import (
	"errors"
	"testing"
	"time"
)

// No service or interface is created. This exercises the real completed-close
// branch after a failed pass, and a concurrent waiter at that same boundary.
func TestLerNETCompletedCloseRetainsActualFailure(t *testing.T) {
	failure := errors.New("test_native_cleanup_failed")
	b := &Box{lernetCloseResult: failure}
	b.closed.Store(true)
	for i := 0; i < 2; i++ {
		if err := b.Close(); err != failure {
			t.Fatalf("close failure replaced by %v", err)
		}
	}
}

func TestLerNETConcurrentCloseCannotConfirmBeforeFirstPassCompletes(t *testing.T) {
	b := &Box{}
	b.lernetCloseMu.Lock()
	result := make(chan error, 1)
	go func() { result <- b.Close() }()
	select {
	case <-result:
		t.Fatal("drain result returned while cleanup incomplete")
	case <-time.After(25 * time.Millisecond):
	}
	failure := errors.New("test_native_cleanup_failed")
	b.closed.Store(true)
	b.lernetCloseResult = failure
	b.lernetCloseMu.Unlock()
	select {
	case err := <-result:
		if err != failure {
			t.Fatal("completed failure lost")
		}
	case <-time.After(time.Second):
		t.Fatal("completed close waiter stuck")
	}
}
