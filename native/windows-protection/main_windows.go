//go:build windows

// The auto-start service preserves base WFP blocks and accepts only elevated,
// local named-pipe commands. Explicit prepare owns the Wintun creator; native
// opens its metadata. Dynamic permits are revoked BEFORE releasing that creator.
// Startup never adopts a stale adapter or removes persistent base blocks.
package main

import (
	"fmt"
	"os"
	"sync"
	"syscall"
	"unsafe"
)

const serviceName = "LerNETProtection"

type serviceTableEntry struct {
	name *uint16
	main uintptr
}

type serviceStatus struct {
	serviceType uint32
	state       uint32
	accepted    uint32
	win32Exit   uint32
	serviceExit uint32
	checkpoint  uint32
	waitHint    uint32
}

var (
	advapi       = syscall.NewLazyDLL("advapi32.dll")
	dispatch     = advapi.NewProc("StartServiceCtrlDispatcherW")
	register     = advapi.NewProc("RegisterServiceCtrlHandlerExW")
	setStatus    = advapi.NewProc("SetServiceStatus")
	statusHandle uintptr
	stopOnce     sync.Once
	stopped      = make(chan struct{})
)

func report(state, accepted uint32) {
	s := serviceStatus{serviceType: 0x10, state: state, accepted: accepted}
	setStatus.Call(statusHandle, uintptr(unsafe.Pointer(&s)))
}

func control(command, eventType, eventData, context uintptr) uintptr {
	if command == 1 || command == 5 {
		report(3, 0) // STOP_PENDING; keep all persistent filters intact.
		stopOnce.Do(func() { close(stopped) })
	}
	return 0
}

func runService(argc uintptr, argv uintptr) uintptr {
	name, _ := syscall.UTF16PtrFromString(serviceName)
	statusHandle, _, _ = register.Call(uintptr(unsafe.Pointer(name)), syscall.NewCallback(control), 0)
	if statusHandle == 0 {
		return 0
	}
	report(2, 0) // START_PENDING
	pipe, err := createControlPipe()
	if err != nil {
		report(1, 0)
		return 0
	}
	owner := &guardian{}
	go owner.serve(pipe)
	go owner.watch()
	report(4, 1|4) // RUNNING, accepts STOP and SHUTDOWN
	<-stopped
	if err := owner.shutdown(); err != nil {
		return 0
	}
	report(1, 0) // STOPPED; filter deletion is exclusively explicit recovery.
	return 0
}

func main() {
	// Exclude the working directory and user PATH for any delayed native load.
	okSearch, _, _ := syscall.NewLazyDLL("kernel32.dll").NewProc("SetDefaultDllDirectories").Call(0x1000)
	if okSearch == 0 {
		os.Exit(3)
	}
	if len(os.Args) == 2 && os.Args[1] == "--uninstall" {
		if err := uninstallOwnProtection(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if len(os.Args) == 3 && os.Args[1] == "--control" {
		if err := guardianClient(os.Args[2]); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if len(os.Args) != 1 {
		os.Exit(2)
	}
	name, err := syscall.UTF16PtrFromString(serviceName)
	if err != nil {
		os.Exit(1)
	}
	table := [2]serviceTableEntry{{name: name, main: syscall.NewCallback(runService)}, {}}
	ok, _, _ := dispatch.Call(uintptr(unsafe.Pointer(&table[0])))
	if ok == 0 {
		os.Exit(1)
	}
}
