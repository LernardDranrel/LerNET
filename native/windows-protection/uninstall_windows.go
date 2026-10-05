//go:build windows

package main

import (
	"errors"
	"fmt"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

type windowsGUID struct {
	data1 uint32
	data2 uint16
	data3 uint16
	data4 [8]byte
}

var ownProvider = windowsGUID{0x2dbb248a, 0x91c0, 0x45b4, [8]byte{0x96, 0xcc, 0xdd, 0x0a, 0x1b, 0x4e, 0x2b, 0x8a}}
var ownSublayer = windowsGUID{0x6fa5577e, 0x591a, 0x41aa, [8]byte{0x8d, 0x9c, 0xbb, 0x0a, 0x16, 0xd8, 0xe4, 0xda}}

func nativeResult(label string, code uintptr) error {
	if code != 0 {
		return fmt.Errorf("%s: Windows code %d", label, uint32(code))
	}
	return nil
}

// Explicit uninstall only. The regular SCM service never invokes this path.
// The native layout is shared with the JVM bridge and verified by ABI tests.
func recoverOwnFilters() error {
	wfp := syscall.NewLazyDLL("fwpuclnt.dll")
	open := wfp.NewProc("FwpmEngineOpen0")
	closeEngine := wfp.NewProc("FwpmEngineClose0")
	begin := wfp.NewProc("FwpmTransactionBegin0")
	commit := wfp.NewProc("FwpmTransactionCommit0")
	abort := wfp.NewProc("FwpmTransactionAbort0")
	createEnum := wfp.NewProc("FwpmFilterCreateEnumHandle0")
	enum := wfp.NewProc("FwpmFilterEnum0")
	destroyEnum := wfp.NewProc("FwpmFilterDestroyEnumHandle0")
	freeMemory := wfp.NewProc("FwpmFreeMemory0")
	deleteFilter := wfp.NewProc("FwpmFilterDeleteByKey0")
	var engine uintptr
	code, _, _ := open.Call(0, 10, 0, 0, uintptr(unsafe.Pointer(&engine)))
	if err := nativeResult("Open WFP", code); err != nil {
		return err
	}
	defer closeEngine.Call(engine)
	code, _, _ = begin.Call(engine, 0)
	if err := nativeResult("Begin WFP recovery", code); err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			abort.Call(engine)
		}
	}()
	var iterator uintptr
	code, _, _ = createEnum.Call(engine, 0, uintptr(unsafe.Pointer(&iterator)))
	if err := nativeResult("Enumerate WFP", code); err != nil {
		return err
	}
	defer destroyEnum.Call(engine, iterator)
	keys := make([]windowsGUID, 0)
	total := uint32(0)
	for {
		var entries uintptr
		var count uint32
		code, _, _ = enum.Call(engine, iterator, 256, uintptr(unsafe.Pointer(&entries)), uintptr(unsafe.Pointer(&count)))
		if err := nativeResult("Read WFP filters", code); err != nil {
			return err
		}
		if count > 256 || (count > 0 && entries == 0) {
			if entries != 0 {
				freeMemory.Call(uintptr(unsafe.Pointer(&entries)))
			}
			return errors.New("invalid WFP enumeration response")
		}
		for i := uint32(0); i < count; i++ {
			filter := *(*uintptr)(unsafe.Pointer(entries + uintptr(i)*8))
			if filter == 0 {
				continue
			}
			provider := *(*uintptr)(unsafe.Pointer(filter + 40))
			sublayer := *(*windowsGUID)(unsafe.Pointer(filter + 80))
			if provider != 0 && ownsFilter(*(*windowsGUID)(unsafe.Pointer(provider)), sublayer) {
				keys = append(keys, *(*windowsGUID)(unsafe.Pointer(filter)))
			}
		}
		if entries != 0 {
			freeMemory.Call(uintptr(unsafe.Pointer(&entries)))
		}
		total += count
		if total > 100_000 {
			return errors.New("too many Windows filters; recovery was not applied")
		}
		if count < 256 {
			break
		}
	}
	for i := range keys {
		code, _, _ = deleteFilter.Call(engine, uintptr(unsafe.Pointer(&keys[i])))
		if err := nativeResult("Delete own WFP filter", code); err != nil {
			return err
		}
	}
	runtime.KeepAlive(keys)
	code, _, _ = commit.Call(engine)
	if err := nativeResult("Commit WFP recovery", code); err != nil {
		return err
	}
	committed = true
	return nil
}

func ownsFilter(provider, sublayer windowsGUID) bool {
	return provider == ownProvider && sublayer == ownSublayer
}

func protectedInstallRoot() (string, error) {
	// FOLDERID_ProgramFilesX64: the machine folder, not an untrusted environment variable.
	id := windowsGUID{0x6d809377, 0x6af0, 0x444b, [8]byte{0x89, 0x57, 0xa3, 0x77, 0x3f, 0x02, 0x20, 0x0e}}
	shell := syscall.NewLazyDLL("shell32.dll")
	var path *uint16
	result, _, _ := shell.NewProc("SHGetKnownFolderPath").Call(uintptr(unsafe.Pointer(&id)), 0, 0, uintptr(unsafe.Pointer(&path)))
	if path != nil {
		defer syscall.NewLazyDLL("ole32.dll").NewProc("CoTaskMemFree").Call(uintptr(unsafe.Pointer(path)))
	}
	if uint32(result) != 0 || path == nil {
		return "", fmt.Errorf("Program Files folder unavailable (HRESULT %08x)", uint32(result))
	}
	return filepath.Join(readUTF16(path), "LerNETProtection"), nil
}

func readUTF16(text *uint16) string {
	if text == nil {
		return ""
	}
	values := make([]uint16, 0, 260)
	for i := uintptr(0); i < 32_768; i++ {
		value := *(*uint16)(unsafe.Pointer(uintptr(unsafe.Pointer(text)) + i*2))
		if value == 0 {
			return syscall.UTF16ToString(values)
		}
		values = append(values, value)
	}
	return ""
}

func ownsServiceBinary(command, root string) bool {
	if len(command) < 2 || command[0] != '"' || command[len(command)-1] != '"' {
		return false
	}
	path := command[1 : len(command)-1]
	if strings.ContainsAny(path, "\"/\x00") {
		return false
	}
	prefix := strings.TrimRight(root, "\\") + "\\"
	if len(path) <= len(prefix) || !strings.EqualFold(path[:len(prefix)], prefix) {
		return false
	}
	return regexp.MustCompile(`(?i)^[a-f0-9]{64}\\lernet-protection-service\.exe$`).MatchString(path[len(prefix):])
}

func openOwnedService(root string) (uintptr, func(), error) {
	manager, _, err := advapi.NewProc("OpenSCManagerW").Call(0, 0, 1)
	if manager == 0 {
		return 0, nil, fmt.Errorf("open SCM: %w", err)
	}
	closeHandle := advapi.NewProc("CloseServiceHandle")
	name, _ := syscall.UTF16PtrFromString(serviceName)
	service, _, err := advapi.NewProc("OpenServiceW").Call(manager, uintptr(unsafe.Pointer(name)), 0x10000|0x20|0x4|0x1)
	if service == 0 {
		closeHandle.Call(manager)
		if err == syscall.Errno(1060) {
			return 0, func() {}, nil
		}
		return 0, nil, fmt.Errorf("open LerNET service: %w", err)
	}
	cleanup := func() { closeHandle.Call(service); closeHandle.Call(manager) }
	query := advapi.NewProc("QueryServiceConfigW")
	var needed uint32
	query.Call(service, 0, 0, uintptr(unsafe.Pointer(&needed)))
	if needed < 64 || needed > 65_536 {
		cleanup()
		return 0, nil, errors.New("invalid service configuration")
	}
	data := make([]byte, needed)
	ptr := uintptr(unsafe.Pointer(&data[0]))
	ok, _, err := query.Call(service, ptr, uintptr(needed), uintptr(unsafe.Pointer(&needed)))
	if ok == 0 {
		cleanup()
		return 0, nil, fmt.Errorf("read service configuration: %w", err)
	}
	typeID := *(*uint32)(unsafe.Pointer(ptr))
	command := readUTF16(*(**uint16)(unsafe.Pointer(ptr + 16)))
	account := readUTF16(*(**uint16)(unsafe.Pointer(ptr + 48)))
	runtime.KeepAlive(data)
	if typeID != 0x10 || !strings.EqualFold(account, "LocalSystem") || !ownsServiceBinary(command, root) {
		cleanup()
		return 0, nil, errors.New("same-named service is not owned by LerNET; it was not changed")
	}
	return service, cleanup, nil
}

func uninstallOwnProtection() error {
	root, err := protectedInstallRoot()
	if err != nil {
		return err
	}
	// Validate ownership before recovery; no same-named foreign service is adopted.
	service, closeHandles, err := openOwnedService(root)
	if err != nil {
		return err
	}
	defer closeHandles()
	if service == 0 {
		return recoverOwnFilters() // Already absent; recovery is idempotent.
	}
	var status serviceStatus
	ok, _, err := advapi.NewProc("ControlService").Call(service, 1, uintptr(unsafe.Pointer(&status)))
	if ok == 0 && err != syscall.Errno(1062) {
		return fmt.Errorf("stop own service: %w", err)
	}
	// Stop the owning dynamic session and native process before removing base blocks.
	// A live guardian owns the creator and must revoke permits BEFORE freeing its LUID.
	deadline := time.Now().Add(30 * time.Second)
	for {
		var processStatus [36]byte
		var needed uint32
		ok, _, err = advapi.NewProc("QueryServiceStatusEx").Call(service, 0, uintptr(unsafe.Pointer(&processStatus[0])), 36, uintptr(unsafe.Pointer(&needed)))
		if ok == 0 {
			return fmt.Errorf("read stopped own service: %w", err)
		}
		if *(*uint32)(unsafe.Pointer(&processStatus[4])) == 1 {
			break
		}
		if time.Now().After(deadline) {
			return errors.New("own guardian did not stop; recovery not applied")
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err := recoverOwnFilters(); err != nil {
		return err
	}
	ok, _, err = advapi.NewProc("DeleteService").Call(service)
	if ok == 0 && err != syscall.Errno(1072) { // Already marked for deletion.
		return fmt.Errorf("delete own service: %w", err)
	}
	// User profiles and other providers/services are never enumerated for deletion.
	return nil
}
