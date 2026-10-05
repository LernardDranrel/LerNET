package expert

import (
	"context"
	"errors"
	"os"

	"golang.org/x/sys/windows"
)

// The retained process object, rather than a repeatedly looked-up PID, owns
// this core. PID reuse can never adopt a new desktop process.
func WatchOwner(ctx context.Context, cancel context.CancelFunc, pid uint32, startedAtMs int64) (func(), error) {
	if pid == 0 || pid == uint32(os.Getpid()) || startedAtMs <= 0 {
		return nil, errors.New("expert_owner_invalid")
	}
	handle, err := windows.OpenProcess(windows.SYNCHRONIZE|windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return nil, errors.New("expert_owner_unavailable")
	}
	var created, exited, kernel, user windows.Filetime
	if windows.GetProcessTimes(handle, &created, &exited, &kernel, &user) != nil || created.Nanoseconds()/1_000_000 != startedAtMs {
		windows.CloseHandle(handle)
		return nil, errors.New("expert_owner_identity_changed")
	}
	stop := make(chan struct{})
	go func() {
		defer windows.CloseHandle(handle)
		for {
			result, err := windows.WaitForSingleObject(handle, 250)
			if err != nil || result == windows.WAIT_OBJECT_0 {
				cancel()
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-stop:
				return
			default:
			}
		}
	}()
	return func() { close(stop) }, nil
}
