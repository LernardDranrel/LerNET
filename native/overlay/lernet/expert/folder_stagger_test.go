package expert

import (
	"testing"
	"time"
)

func TestLerNETFolderCandidatesAreStaggered(t *testing.T) {
	if candidateSlots > 2 {
		t.Fatalf("at most two folder candidates may be probed at once, got %d", candidateSlots)
	}
	for i := 0; i < 1000; i++ {
		gap := candidateGap()
		if gap < 150*time.Millisecond || gap > 400*time.Millisecond {
			t.Fatalf("gap %v outside 150–400 ms", gap)
		}
	}
}
