//go:build windows

package main

import (
	"strings"
	"testing"
	"unsafe"
)

// ABI-only tests. No SCM API is invoked and the service is never installed.
func TestWindowsSCMABI(t *testing.T) {
	if unsafe.Sizeof(uintptr(0)) != 8 {
		t.Fatal("guard distribution is Windows amd64 only")
	}
	if unsafe.Sizeof(serviceTableEntry{}) != 16 {
		t.Fatal("invalid SERVICE_TABLE_ENTRYW layout")
	}
	if unsafe.Sizeof(serviceStatus{}) != 28 {
		t.Fatal("invalid SERVICE_STATUS layout")
	}
	if unsafe.Offsetof(serviceStatus{}.state) != 4 {
		t.Fatal("invalid state offset")
	}
	if serviceName != "LerNETProtection" {
		t.Fatal("provider and SCM service identity must remain stable")
	}
}

// Independent native-alignment check for the WinSDK layout used by the JVM bridge.
func TestWFPX64ABI(t *testing.T) {
	type guid struct {
		data1        uint32
		data2, data3 uint16
		data4        [8]byte
	}
	type display struct{ name, description uintptr }
	type blob struct {
		size uint32
		data uintptr
	}
	type value struct {
		kind  uint32
		union uintptr
	}
	type action struct {
		kind uint32
		key  guid
	}
	type filter struct {
		key             guid
		display         display
		flags           uint32
		provider        uintptr
		data            blob
		layer, sublayer guid
		weight          value
		count           uint32
		conditions      uintptr
		action          action
		context         [2]uint64
		reserved        uintptr
		id              uint64
		effective       value
	}
	type condition struct {
		key   guid
		match uint32
		value value
	}
	f := filter{}
	if unsafe.Sizeof(f) != 200 || unsafe.Sizeof(condition{}) != 40 {
		t.Fatal("unexpected FWPM_FILTER0/CONDITION0 alignment")
	}
	if unsafe.Offsetof(f.provider) != 40 || unsafe.Offsetof(f.layer) != 64 || unsafe.Offsetof(f.sublayer) != 80 {
		t.Fatal("unexpected identity offsets")
	}
	if unsafe.Offsetof(f.weight) != 96 || unsafe.Offsetof(f.count) != 112 || unsafe.Offsetof(f.conditions) != 120 {
		t.Fatal("unexpected condition offsets")
	}
	if unsafe.Offsetof(f.action) != 128 || unsafe.Offsetof(f.context) != 152 || unsafe.Offsetof(f.id) != 176 {
		t.Fatal("unexpected action/context offsets")
	}
}

func TestRecoveryOnlyOwnsExactProviderAndSublayer(t *testing.T) {
	if !ownsFilter(ownProvider, ownSublayer) {
		t.Fatal("owned filter not recognized")
	}
	foreignProvider := ownProvider
	foreignProvider.data1++
	foreignSublayer := ownSublayer
	foreignSublayer.data1++
	if ownsFilter(foreignProvider, ownSublayer) || ownsFilter(ownProvider, foreignSublayer) {
		t.Fatal("foreign filter adopted")
	}
	if unsafe.Sizeof(windowsGUID{}) != 16 {
		t.Fatal("GUID ABI mismatch")
	}
}

func TestUninstallerRejectsForeignOrAmbiguousServicePaths(t *testing.T) {
	root := `C:\Program Files\LerNETProtection`
	owned := `"` + root + `\` + strings.Repeat("a", 64) + `\lernet-protection-service.exe"`
	if !ownsServiceBinary(owned, root) {
		t.Fatal("own secure service not recognized")
	}
	for _, candidate := range []string{
		owned + " --uninstall", strings.Trim(owned, `"`),
		strings.ReplaceAll(owned, "LerNETProtection", "Foreign"),
		strings.ReplaceAll(owned, "lernet-protection-service.exe", "cmd.exe"),
		strings.ReplaceAll(owned, strings.Repeat("a", 64), ".."),
		strings.ReplaceAll(owned, `\`, "/"),
	} {
		if ownsServiceBinary(candidate, root) {
			t.Fatalf("unsafe service adopted: %s", candidate)
		}
	}
}
