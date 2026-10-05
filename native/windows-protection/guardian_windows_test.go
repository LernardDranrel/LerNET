//go:build windows

package main

import (
	"encoding/binary"
	"errors"
	"reflect"
	"testing"
	"unsafe"
)

func TestMibIfRow2IdentityLayout(t *testing.T) {
	var header struct {
		luid  uint64
		index uint32
		guid  windowsGUID
		alias [257]uint16
	}
	if unsafe.Sizeof(header.guid) != 16 || unsafe.Alignof(header.guid) != 4 ||
		unsafe.Offsetof(header.index) != mibIfRow2InterfaceIndexOffset ||
		unsafe.Offsetof(header.guid) != mibIfRow2InterfaceGUIDOffset ||
		unsafe.Offsetof(header.alias) != mibIfRow2AliasOffset {
		t.Fatal("guardian identity offsets disagree with the MIB_IF_ROW2 ABI")
	}
}

func TestInterfaceIdentityDecodesNativeMibIfRow2(t *testing.T) {
	// Native Win32 byte layout, independent of the decoder's offset constants.
	var row [1352]byte
	binary.LittleEndian.PutUint64(row[0:8], 0x1234567800000000)
	binary.LittleEndian.PutUint32(row[8:12], 1337)
	nativeGUID := [16]byte{0xd1, 0x57, 0x8d, 0xc3, 0xa7, 0x05, 0x33, 0x4c, 0x90, 0x4f, 0x7f, 0xbc, 0xee, 0xe6, 0x0e, 0x82}
	copy(row[12:28], nativeGUID[:])
	name := "LerNET-Guard-owned-tun"
	for i, char := range name {
		binary.LittleEndian.PutUint16(row[28+i*2:], uint16(char))
	}
	index, guid, alias := decodeInterfaceIdentity(&row)
	if index != 1337 || formatGUID(guid) != "c38d57d1-05a7-4c33-904f-7fbceee60e82" || alias != name {
		t.Fatalf("wrong owned adapter identity: index=%d guid=%s alias=%q", index, formatGUID(guid), alias)
	}
}

func TestCreatorPinsLuidUntilPermissionRevokeSucceeds(t *testing.T) {
	held := true
	events := []string{}
	failure := errors.New("WFP unavailable")
	if err := revokeBeforeCreatorClose(func() error { events = append(events, "revoke-failed"); return failure }, func() { held = false }); err != failure || !held {
		t.Fatal("LUID creator was released while stale permission remained")
	}
	if err := revokeBeforeCreatorClose(func() error { events = append(events, "permissions-gone"); return nil }, func() { events = append(events, "creator-close"); held = false }); err != nil || held {
		t.Fatal("successful revoke did not release creator")
	}
	if !reflect.DeepEqual(events, []string{"revoke-failed", "permissions-gone", "creator-close"}) {
		t.Fatal(events)
	}
}

func TestRpcFailureCannotProveDynamicPermissionsRemoved(t *testing.T) {
	missing := uintptr(0x80320003)
	if !filterRemovalProved([]uintptr{missing, missing, missing, missing}) {
		t.Fatal("four independently missing filter IDs must prove removal")
	}
	for _, result := range []uintptr{0, 1722, 1726, 6, 0x80010108, 0x80320010} {
		if filterRemovalProved([]uintptr{missing, missing, missing, result}) {
			t.Fatalf("0x%x fabricated permission removal", result)
		}
	}
	if filterRemovalProved([]uintptr{missing, missing, missing}) {
		t.Fatal("incomplete removal evidence accepted")
	}
}

func TestControlSecurityAttributesMatchesWindowsAmd64(t *testing.T) {
	var value securityAttributes
	if unsafe.Sizeof(value) != 24 || unsafe.Offsetof(value.descriptor) != 8 || unsafe.Offsetof(value.inherit) != 16 {
		t.Fatal("SECURITY_ATTRIBUTES ABI does not match WinSDK x64")
	}
}
func TestGuardianAcceptsOnlyExactlyFourUniqueAleFilters(t *testing.T) {
	layers := []string{"c38d57d1-05a7-4c33-904f-7fbceee60e82", "4a72393b-319f-44bc-84c3-ba54dcb3b6b4", "e1cd9fe7-f4b5-4273-96c0-592e487b8650", "a3b42c97-9f04-4672-b87e-cee9c483257f"}
	filters := make([]guardianFilter, 4)
	for i, layer := range layers {
		filters[i] = guardianFilter{Key: layer, Layer: layer}
	}
	if e := validatedTunFilters(filters); e != nil {
		t.Fatal(e)
	}
	duplicate := append([]guardianFilter(nil), filters...)
	duplicate[3].Layer = duplicate[0].Layer
	if validatedTunFilters(duplicate) == nil {
		t.Fatal("duplicate layer accepted")
	}
	duplicate = append([]guardianFilter(nil), filters...)
	duplicate[3].Key = duplicate[0].Key
	if validatedTunFilters(duplicate) == nil {
		t.Fatal("duplicate key accepted")
	}
	if validatedTunFilters(filters[:3]) == nil {
		t.Fatal("incomplete family/direction accepted")
	}
}
func TestGuardianNeverAdoptsANameOnlyOrExternalCore(t *testing.T) {
	root := `C:\Program Files\LerNETProtection`
	for _, path := range []string{`lernet-core.exe`, `C:\Users\Public\lernet-core.exe`, root + `\..\lernet-core.exe`} {
		if ownCorePath(path, root) {
			t.Fatalf("adopted %s", path)
		}
	}
	if !ownCorePath(root+`\`+"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"+`\lernet-core.exe`, root) {
		t.Fatal("protected bundle rejected")
	}
}
func TestGuardianGuidRoundTripIsCanonical(t *testing.T) {
	value := "C38D57D1-05A7-4C33-904F-7FBCEEE60E82"
	id, e := parseGUID(value)
	if e != nil {
		t.Fatal(e)
	}
	if formatGUID(id) != "c38d57d1-05a7-4c33-904f-7fbceee60e82" {
		t.Fatal(formatGUID(id))
	}
}
