//go:build windows && with_purego

package expert

import (
	"errors"
	"github.com/sagernet/cronet-go"
	"golang.org/x/sys/windows"
	"os"
	"path/filepath"
)

func initializeLibraries() error {
	// Exclude the current directory and user PATH from native dependency lookup.
	// The platform host validates and protects this executable and its sibling DLL.
	proc := windows.NewLazySystemDLL("kernel32.dll").NewProc("SetDefaultDllDirectories")
	if err := proc.Find(); err != nil {
		return errors.New("safe_dll_search_unavailable")
	}
	ok, _, _ := proc.Call(0x00001000)
	if ok == 0 {
		return errors.New("safe_dll_search_failed")
	}
	exe, err := os.Executable()
	if err != nil {
		return errors.New("native_executable_path_unavailable")
	}
	if err = cronet.LoadLibrary(filepath.Join(filepath.Dir(exe), "libcronet.dll")); err != nil {
		return errors.New("native_cronet_unavailable")
	}
	return nil
}
