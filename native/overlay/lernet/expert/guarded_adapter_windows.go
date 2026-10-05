package expert

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"time"
	"unsafe"

	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/windows"
)

func guardedAdapterContext(ctx context.Context, adapter *tun.LerNETGuardedAdapter) (context.Context, error) {
	if adapter == nil {
		return ctx, nil
	}
	if adapter.PID != uint32(os.Getpid()) || adapter.StartedAtMs <= 0 || adapter.TunName == "" || adapter.LUID == 0 || adapter.IfIndex == 0 {
		return nil, errors.New("guarded_adapter_invalid")
	}
	if _, err := windows.GUIDFromString("{" + strings.Trim(adapter.GUID, "{}") + "}"); err != nil {
		return nil, errors.New("guarded_adapter_invalid")
	}
	definition := *adapter
	definition.BeforeClose = func() error { return guardianOperation("revoke_owned_tun", definition.PID, definition.StartedAtMs) }
	definition.AfterClose = func() error { return guardianOperation("release_owned_tun", definition.PID, definition.StartedAtMs) }
	return service.ContextWith[*tun.LerNETGuardedAdapter](service.ExtendContext(ctx), &definition), nil
}

func validateGuardedIngress(ctx context.Context, options *option.Options) error {
	definition := service.FromContext[*tun.LerNETGuardedAdapter](ctx)
	if definition == nil {
		return nil
	}
	inbound := options.Inbounds[0].Options.(*option.TunInboundOptions)
	if inbound.InterfaceName != definition.TunName {
		return errors.New("guarded_adapter_invalid")
	}
	return nil
}

// Also covers cancellation before tun.New: the guardian may already own the
// device even though no native inbound was constructed. Repeated service
// operations for a retired creation identity are harmless acknowledged no-ops.
func finalizeGuardedAdapter(ctx context.Context) error {
	definition := service.FromContext[*tun.LerNETGuardedAdapter](ctx)
	if definition == nil {
		return nil
	}
	if err := definition.BeforeClose(); err != nil {
		return err
	}
	return definition.AfterClose()
}

// An overlapped RPC has one total five-second deadline. The service checks the
// pipe client's actual PID and pins the matching process creation identity.
func guardianOperation(operation string, pid uint32, started int64) error {
	path, _ := windows.UTF16PtrFromString(`\\.\pipe\LerNETProtection.Control.v2`)
	deadline := time.Now().Add(5 * time.Second)
	var handle windows.Handle
	for {
		var err error
		handle, err = windows.CreateFile(path, windows.GENERIC_READ|windows.GENERIC_WRITE, 0, nil, windows.OPEN_EXISTING, windows.FILE_FLAG_OVERLAPPED, 0)
		if err == nil {
			break
		}
		remaining := time.Until(deadline)
		if err != windows.ERROR_PIPE_BUSY || remaining <= 0 {
			return errors.New("guardian_control_unavailable")
		}
		ok, _, _ := guardianWaitNamedPipe.Call(uintptr(unsafe.Pointer(path)), uintptr((remaining+time.Millisecond-1)/time.Millisecond))
		if ok == 0 {
			return errors.New("guardian_control_unavailable")
		}
	}
	defer windows.CloseHandle(handle)
	request, _ := json.Marshal(struct {
		Operation   string `json:"operation"`
		PID         uint32 `json:"pid"`
		StartedAtMs int64  `json:"started_at_ms"`
	}{operation, pid, started})
	request = append(request, '\n')
	written, err := guardianIO(handle, request, true, deadline)
	if err != nil {
		return err
	}
	if int(written) != len(request) {
		return errors.New("guardian_control_invalid_response")
	}
	response := make([]byte, 0, 1024)
	for len(response) < 8192 {
		buffer := make([]byte, 1024)
		count, readErr := guardianIO(handle, buffer, false, deadline)
		if readErr != nil {
			return readErr
		}
		if count == 0 {
			return errors.New("guardian_control_invalid_response")
		}
		response = append(response, buffer[:count]...)
		if newline := bytes.IndexByte(response, '\n'); newline >= 0 {
			var result struct {
				OK bool `json:"ok"`
			}
			if json.Unmarshal(response[:newline], &result) != nil || !result.OK {
				return errors.New("guardian_control_rejected")
			}
			return nil
		}
	}
	return errors.New("guardian_control_invalid_response")
}

var guardianWaitNamedPipe = windows.NewLazySystemDLL("kernel32.dll").NewProc("WaitNamedPipeW")

func guardianIO(handle windows.Handle, buffer []byte, write bool, deadline time.Time) (uint32, error) {
	event, err := windows.CreateEvent(nil, 1, 0, nil)
	if err != nil {
		return 0, errors.New("guardian_control_unavailable")
	}
	defer windows.CloseHandle(event)
	overlapped := windows.Overlapped{HEvent: event}
	var count uint32
	if write {
		err = windows.WriteFile(handle, buffer, &count, &overlapped)
	} else {
		err = windows.ReadFile(handle, buffer, &count, &overlapped)
	}
	if err == nil {
		return count, nil
	}
	if err != windows.ERROR_IO_PENDING {
		return 0, errors.New("guardian_control_unavailable")
	}
	remaining := time.Until(deadline)
	var result uint32
	if remaining > 0 {
		result, err = windows.WaitForSingleObject(event, uint32((remaining+time.Millisecond-1)/time.Millisecond))
	} else {
		result = uint32(windows.WAIT_TIMEOUT)
	}
	if err != nil || result != windows.WAIT_OBJECT_0 {
		_ = windows.CancelIoEx(handle, &overlapped)
		// The OVERLAPPED and its Go buffer cannot be freed before cancellation is
		// acknowledged. The CLI owner watchdog independently bounds process exit.
		_ = windows.GetOverlappedResult(handle, &overlapped, &count, true)
		return 0, errors.New("guardian_control_timeout")
	}
	if windows.GetOverlappedResult(handle, &overlapped, &count, false) != nil {
		return 0, errors.New("guardian_control_unavailable")
	}
	return count, nil
}
