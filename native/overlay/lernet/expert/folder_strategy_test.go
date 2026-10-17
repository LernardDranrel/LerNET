package expert

import (
	"testing"
	"time"
)

func TestFastSwitchNeedsBothGainsAndTenMinutes(t *testing.T) {
	f := &folderGate{}
	now := time.Now()
	if !f.fastSwitchAllowed(200, 150, now) {
		t.Fatal("50 ms and 25 % must switch")
	}
	if f.fastSwitchAllowed(400, 350, now) {
		t.Fatal("50 ms but only 12.5 % must not switch")
	}
	if f.fastSwitchAllowed(100, 70, now) {
		t.Fatal("30 % but only 30 ms must not switch")
	}
	f.lastFastSwitch = now.Add(-9 * time.Minute)
	if f.fastSwitchAllowed(300, 100, now) {
		t.Fatal("second switch within 10 minutes")
	}
	f.lastFastSwitch = now.Add(-10 * time.Minute)
	if !f.fastSwitchAllowed(300, 100, now) {
		t.Fatal("switch allowed after 10 minutes")
	}
}
