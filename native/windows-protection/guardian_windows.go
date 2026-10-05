//go:build windows

package main

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

const controlPipe = `\\.\pipe\LerNETProtection.Control.v2`

type guardianRequest struct {
	Operation       string           `json:"operation"`
	PID             uint32           `json:"pid"`
	StartedAtMillis int64            `json:"started_at_ms"`
	CorePath        string           `json:"core_path"`
	TunName         string           `json:"tun_name"`
	LUID            uint64           `json:"luid"`
	GUID            string           `json:"guid"`
	Filters         []guardianFilter `json:"filters"`
}
type guardianFilter struct {
	Key   string `json:"key"`
	Layer string `json:"layer"`
}
type guardianOwner struct {
	PID             uint32 `json:"pid"`
	StartedAtMillis int64  `json:"started_at_ms"`
	TunName         string `json:"tun_name"`
	LUID            uint64 `json:"luid"`
	Index           uint32 `json:"if_index"`
	GUID            string `json:"guid"`
}
type guardianResponse struct {
	OK    bool           `json:"ok"`
	Error string         `json:"error,omitempty"`
	Owner *guardianOwner `json:"owner,omitempty"`
	Keys  []string       `json:"keys,omitempty"`
}

type guardianLease struct {
	owner        guardianOwner
	process      uintptr
	adapter      uintptr // WintunCreateAdapter creator, never merely OpenAdapter metadata.
	library      uintptr
	closeAdapter uintptr
	engine       uintptr // Dynamic WFP session owned by THIS service process.
	keys         []string
	filters      []guardianFilter
	filterIDs    map[string]uint64
}
type guardian struct {
	mu    sync.Mutex
	lease *guardianLease
}

var kernel = syscall.NewLazyDLL("kernel32.dll")
var fwp = syscall.NewLazyDLL("fwpuclnt.dll")
var iphelper = syscall.NewLazyDLL("iphlpapi.dll")

func parseGUID(text string) (windowsGUID, error) {
	var id windowsGUID
	if !regexp.MustCompile(`(?i)^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$`).MatchString(text) {
		return id, errors.New("invalid GUID")
	}
	b, e := hex.DecodeString(strings.ReplaceAll(text, "-", ""))
	if e != nil {
		return id, e
	}
	id.data1 = binary.BigEndian.Uint32(b)
	id.data2 = binary.BigEndian.Uint16(b[4:])
	id.data3 = binary.BigEndian.Uint16(b[6:])
	copy(id.data4[:], b[8:])
	return id, nil
}
func formatGUID(id windowsGUID) string {
	return fmt.Sprintf("%08x-%04x-%04x-%02x%02x-%x", id.data1, id.data2, id.data3, id.data4[0], id.data4[1], id.data4[2:])
}
func ownCorePath(path, root string) bool {
	if strings.ContainsAny(path, `"/`+"\x00") {
		return false
	}
	prefix := strings.TrimRight(root, `\`) + `\`
	return len(path) > len(prefix) && strings.EqualFold(path[:len(prefix)], prefix) &&
		regexp.MustCompile(`(?i)^[a-f0-9]{64}\\lernet-core\.exe$`).MatchString(path[len(prefix):])
}
func safeLocalFile(path string) error {
	for current := path; current != ""; current = filepath.Dir(current) {
		name, e := syscall.UTF16PtrFromString(current)
		if e != nil {
			return e
		}
		attrs, _, e := kernel.NewProc("GetFileAttributesW").Call(uintptr(unsafe.Pointer(name)))
		if attrs == 0xffffffff {
			return fmt.Errorf("protected file attributes: %w", e)
		}
		if attrs&0x400 != 0 {
			return errors.New("protected file contains reparse point")
		}
		parent := filepath.Dir(current)
		if parent == current {
			break
		}
	}
	return nil
}
func coreProcess(request guardianRequest) (uintptr, error) {
	if request.PID == 0 || request.StartedAtMillis <= 0 {
		return 0, errors.New("native process identity absent")
	}
	root, e := protectedInstallRoot()
	if e != nil {
		return 0, e
	}
	if !ownCorePath(request.CorePath, root) {
		return 0, errors.New("native image is outside protected bundle")
	}
	if e = safeLocalFile(request.CorePath); e != nil {
		return 0, e
	}
	h, _, e := kernel.NewProc("OpenProcess").Call(0x1000|0x100000|1, 0, uintptr(request.PID))
	if h == 0 {
		return 0, fmt.Errorf("open pinned native process: %w", e)
	}
	ok := false
	defer func() {
		if !ok {
			kernel.NewProc("CloseHandle").Call(h)
		}
	}()
	var created, exit, kernelTime, user [2]uint32
	r, _, e := kernel.NewProc("GetProcessTimes").Call(h, uintptr(unsafe.Pointer(&created)), uintptr(unsafe.Pointer(&exit)), uintptr(unsafe.Pointer(&kernelTime)), uintptr(unsafe.Pointer(&user)))
	if r == 0 {
		return 0, e
	}
	stamp := ((uint64(created[1])<<32)|uint64(created[0]))/10000 - 11644473600000
	if int64(stamp) != request.StartedAtMillis {
		return 0, errors.New("native PID creation time changed")
	}
	name := make([]uint16, 32768)
	size := uint32(len(name))
	r, _, e = kernel.NewProc("QueryFullProcessImageNameW").Call(h, 0, uintptr(unsafe.Pointer(&name[0])), uintptr(unsafe.Pointer(&size)))
	if r == 0 || !strings.EqualFold(syscall.UTF16ToString(name[:size]), request.CorePath) {
		return 0, errors.New("native process image changed")
	}
	if !processAlive(h) {
		return 0, errors.New("native process already exited")
	}
	if e = allowedCoreApp(request.CorePath); e != nil {
		return 0, e
	}
	ok = true
	return h, nil
}
func processAlive(handle uintptr) bool {
	value, _, _ := kernel.NewProc("WaitForSingleObject").Call(handle, 0)
	return value == 258
}
func ownedCoreCondition(filter uintptr, blob []byte) bool {
	provider := *(*uintptr)(unsafe.Pointer(filter + 40))
	if provider == 0 || !ownsFilter(*(*windowsGUID)(unsafe.Pointer(provider)), *(*windowsGUID)(unsafe.Pointer(filter + 80))) {
		return false
	}
	if *(*uint32)(unsafe.Pointer(filter + 32))&1 == 0 || *(*uint32)(unsafe.Pointer(filter + 128)) != 0x1002 || *(*uint32)(unsafe.Pointer(filter + 112)) != 1 {
		return false
	}
	condition := *(*uintptr)(unsafe.Pointer(filter + 120))
	app, _ := parseGUID("d78e1e87-8644-4ea5-9437-d809ecefc971")
	if condition == 0 || *(*windowsGUID)(unsafe.Pointer(condition)) != app || *(*uint32)(unsafe.Pointer(condition + 16)) != 0 || *(*uint32)(unsafe.Pointer(condition + 24)) != 12 {
		return false
	}
	value := *(*uintptr)(unsafe.Pointer(condition + 32))
	if value == 0 {
		return false
	}
	count := *(*uint32)(unsafe.Pointer(value))
	data := *(*uintptr)(unsafe.Pointer(value + 8))
	if count > 65536 || data == 0 {
		return false
	}
	return bytes.Equal(unsafe.Slice((*byte)(unsafe.Pointer(data)), int(count)), blob)
}
func allowedCoreApp(path string) error {
	var engine uintptr
	r, _, _ := fwp.NewProc("FwpmEngineOpen0").Call(0, 10, 0, 0, uintptr(unsafe.Pointer(&engine)))
	if e := nativeResult("open base WFP", r); e != nil {
		return e
	}
	defer fwp.NewProc("FwpmEngineClose0").Call(engine)
	name, _ := syscall.UTF16PtrFromString(path)
	var app uintptr
	r, _, _ = fwp.NewProc("FwpmGetAppIdFromFileName0").Call(uintptr(unsafe.Pointer(name)), uintptr(unsafe.Pointer(&app)))
	if e := nativeResult("native app identity", r); e != nil {
		return e
	}
	defer fwp.NewProc("FwpmFreeMemory0").Call(uintptr(unsafe.Pointer(&app)))
	count := *(*uint32)(unsafe.Pointer(app))
	data := *(*uintptr)(unsafe.Pointer(app + 8))
	if count > 65536 || data == 0 {
		return errors.New("invalid native app ID")
	}
	blob := append([]byte(nil), unsafe.Slice((*byte)(unsafe.Pointer(data)), int(count))...)
	var iterator uintptr
	r, _, _ = fwp.NewProc("FwpmFilterCreateEnumHandle0").Call(engine, 0, uintptr(unsafe.Pointer(&iterator)))
	if e := nativeResult("enum base WFP", r); e != nil {
		return e
	}
	defer fwp.NewProc("FwpmFilterDestroyEnumHandle0").Call(engine, iterator)
	for page := 0; page < 400; page++ {
		var entries uintptr
		var n uint32
		r, _, _ = fwp.NewProc("FwpmFilterEnum0").Call(engine, iterator, 256, uintptr(unsafe.Pointer(&entries)), uintptr(unsafe.Pointer(&n)))
		if e := nativeResult("read base WFP", r); e != nil {
			return e
		}
		if n > 256 || (n > 0 && entries == 0) {
			return errors.New("invalid WFP page")
		}
		found := false
		for i := uint32(0); i < n; i++ {
			p := *(*uintptr)(unsafe.Pointer(entries + uintptr(i)*8))
			if p != 0 && ownedCoreCondition(p, blob) {
				found = true
				break
			}
		}
		if entries != 0 {
			fwp.NewProc("FwpmFreeMemory0").Call(uintptr(unsafe.Pointer(&entries)))
		}
		if found {
			return nil
		}
		if n < 256 {
			break
		}
	}
	return errors.New("native image has no persistent LerNET core permission")
}
func interfaceIdentity(luid uint64) (uint32, windowsGUID, string, error) {
	var row [1352]byte
	binary.LittleEndian.PutUint64(row[:], luid)
	r, _, _ := iphelper.NewProc("GetIfEntry2").Call(uintptr(unsafe.Pointer(&row[0])))
	if e := nativeResult("read owned interface", r); e != nil {
		return 0, windowsGUID{}, "", e
	}
	index := binary.LittleEndian.Uint32(row[8:])
	id := *(*windowsGUID)(unsafe.Pointer(&row[16]))
	name := readUTF16((*uint16)(unsafe.Pointer(&row[32])))
	runtime.KeepAlive(row)
	return index, id, name, nil
}
func createAdapter(request guardianRequest, process uintptr) (*guardianLease, error) {
	if len(request.TunName) < 1 || len(request.TunName) > 128 || !strings.HasPrefix(request.TunName, "LerNET-") || strings.ContainsAny(request.TunName, "\\/\x00\r\n") {
		return nil, errors.New("invalid owned TUN name")
	}
	libraryPath := filepath.Join(filepath.Dir(request.CorePath), "wintun.dll")
	if e := safeLocalFile(libraryPath); e != nil {
		return nil, e
	}
	name, _ := syscall.UTF16PtrFromString(libraryPath)
	module, _, e := kernel.NewProc("LoadLibraryExW").Call(uintptr(unsafe.Pointer(name)), 0, 0x900)
	if module == 0 {
		return nil, fmt.Errorf("load protected Wintun: %w", e)
	}
	get := func(n string) uintptr {
		s, _ := syscall.BytePtrFromString(n)
		p, _, _ := kernel.NewProc("GetProcAddress").Call(module, uintptr(unsafe.Pointer(s)))
		return p
	}
	open, create, closeAdapter, luidAPI := get("WintunOpenAdapter"), get("WintunCreateAdapter"), get("WintunCloseAdapter"), get("WintunGetAdapterLUID")
	if open == 0 || create == 0 || closeAdapter == 0 || luidAPI == 0 {
		kernel.NewProc("FreeLibrary").Call(module)
		return nil, errors.New("protected Wintun exports absent")
	}
	tun, _ := syscall.UTF16PtrFromString(request.TunName)
	kind, _ := syscall.UTF16PtrFromString("LerNET")
	existing, _, _ := syscall.SyscallN(open, uintptr(unsafe.Pointer(tun)))
	if existing != 0 {
		syscall.SyscallN(closeAdapter, existing)
		kernel.NewProc("FreeLibrary").Call(module)
		return nil, errors.New("same-named adapter already exists; not adopted")
	}
	adapter, _, e := syscall.SyscallN(create, uintptr(unsafe.Pointer(tun)), uintptr(unsafe.Pointer(kind)), 0)
	if adapter == 0 {
		kernel.NewProc("FreeLibrary").Call(module)
		return nil, fmt.Errorf("create guardian-owned Wintun: %w", e)
	}
	var luid uint64
	syscall.SyscallN(luidAPI, adapter, uintptr(unsafe.Pointer(&luid)))
	index, id, alias, e := interfaceIdentity(luid)
	if e != nil || luid == 0 || !strings.EqualFold(alias, request.TunName) {
		syscall.SyscallN(closeAdapter, adapter)
		kernel.NewProc("FreeLibrary").Call(module)
		return nil, errors.New("created adapter identity mismatch")
	}
	return &guardianLease{owner: guardianOwner{request.PID, request.StartedAtMillis, request.TunName, luid, index, formatGUID(id)}, process: process, adapter: adapter, library: module, closeAdapter: closeAdapter}, nil
}
func (l *guardianLease) revoke() error {
	if l.engine == 0 {
		l.keys = nil
		return nil
	}
	r, _, _ := fwp.NewProc("FwpmEngineClose0").Call(l.engine)
	// An RPC failure is not evidence that BFE removed the permits. Retain the
	// creator until a fresh, independent session proves every kernel ID absent.
	if r != 0 {
		if e := l.permissionsAbsent(); e != nil {
			return fmt.Errorf("revoke dynamic TUN permissions (0x%x): %w", r, e)
		}
	}
	l.engine = 0
	l.keys = nil
	l.filters = nil
	l.filterIDs = nil
	return nil
}
func filterRemovalProved(results []uintptr) bool {
	if len(results) != 4 {
		return false
	}
	for _, result := range results {
		if result != 0x80320003 { // FWP_E_FILTER_NOT_FOUND, not a communication error.
			return false
		}
	}
	return true
}
func (l *guardianLease) permissionsAbsent() error {
	if len(l.filterIDs) != 4 {
		return errors.New("dynamic permission identity is incomplete")
	}
	var engine uintptr
	r, _, _ := fwp.NewProc("FwpmEngineOpen0").Call(0, 10, 0, 0, uintptr(unsafe.Pointer(&engine)))
	if e := nativeResult("open fresh WFP removal proof", r); e != nil {
		return e
	}
	defer fwp.NewProc("FwpmEngineClose0").Call(engine)
	results := make([]uintptr, 0, len(l.filterIDs))
	for _, id := range l.filterIDs {
		var filter uintptr
		r, _, _ = fwp.NewProc("FwpmFilterGetById0").Call(engine, uintptr(id), uintptr(unsafe.Pointer(&filter)))
		if filter != 0 {
			fwp.NewProc("FwpmFreeMemory0").Call(uintptr(unsafe.Pointer(&filter)))
		}
		results = append(results, r)
	}
	if !filterRemovalProved(results) {
		return errors.New("dynamic permission removal could not be proved")
	}
	return nil
}
func (l *guardianLease) release() error {
	if e := revokeBeforeCreatorClose(l.revoke, func() {
		if l.adapter != 0 {
			syscall.SyscallN(l.closeAdapter, l.adapter)
			l.adapter = 0
		}
	}); e != nil {
		return e
	}
	if l.library != 0 {
		kernel.NewProc("FreeLibrary").Call(l.library)
		l.library = 0
	}
	if l.process != 0 {
		kernel.NewProc("CloseHandle").Call(l.process)
		l.process = 0
	}
	return nil
}

func revokeBeforeCreatorClose(revoke func() error, closeCreator func()) error {
	if err := revoke(); err != nil {
		return err
	}
	closeCreator()
	return nil
}
func validatedTunFilters(filters []guardianFilter) error {
	if len(filters) != 4 {
		return errors.New("four ALE TUN filters required")
	}
	layers := map[string]bool{"c38d57d1-05a7-4c33-904f-7fbceee60e82": true, "4a72393b-319f-44bc-84c3-ba54dcb3b6b4": true, "e1cd9fe7-f4b5-4273-96c0-592e487b8650": true, "a3b42c97-9f04-4672-b87e-cee9c483257f": true}
	keys := map[string]bool{}
	for _, filter := range filters {
		if _, e := parseGUID(filter.Key); e != nil {
			return e
		}
		layer := strings.ToLower(filter.Layer)
		if !layers[layer] || keys[strings.ToLower(filter.Key)] {
			return errors.New("duplicate or unsupported TUN filter")
		}
		delete(layers, layer)
		keys[strings.ToLower(filter.Key)] = true
	}
	return nil
}
func (l *guardianLease) arm(filters []guardianFilter) error {
	if e := validatedTunFilters(filters); e != nil {
		return e
	}
	if e := l.revoke(); e != nil {
		return e
	}
	var session [72]byte
	binary.LittleEndian.PutUint32(session[32:], 1)
	var engine uintptr
	r, _, _ := fwp.NewProc("FwpmEngineOpen0").Call(0, 10, 0, uintptr(unsafe.Pointer(&session[0])), uintptr(unsafe.Pointer(&engine)))
	if e := nativeResult("open dynamic TUN session", r); e != nil {
		return e
	}
	committed := false
	defer func() {
		if !committed {
			fwp.NewProc("FwpmTransactionAbort0").Call(engine)
			fwp.NewProc("FwpmEngineClose0").Call(engine)
		}
	}()
	r, _, _ = fwp.NewProc("FwpmTransactionBegin0").Call(engine, 0)
	if e := nativeResult("begin TUN permissions", r); e != nil {
		return e
	}
	filterIDs := map[string]uint64{}
	for _, filter := range filters {
		key, _ := parseGUID(filter.Key)
		layer, _ := parseGUID(filter.Layer)
		field, _ := parseGUID("618a9b6d-386b-4136-ad6e-b51587cfb1cd")
		if strings.EqualFold(filter.Layer, "c38d57d1-05a7-4c33-904f-7fbceee60e82") || strings.EqualFold(filter.Layer, "4a72393b-319f-44bc-84c3-ba54dcb3b6b4") {
			field, _ = parseGUID("93ae8f5b-7f6f-4719-98c8-14e97429ef04")
		}
		var native [200]byte
		var condition [40]byte
		weight := uint64(900)
		label, _ := syscall.UTF16PtrFromString("LerNET guardian-owned TUN")
		*(*windowsGUID)(unsafe.Pointer(&native[0])) = key
		*(*uintptr)(unsafe.Pointer(&native[16])) = uintptr(unsafe.Pointer(label))
		*(*uintptr)(unsafe.Pointer(&native[40])) = uintptr(unsafe.Pointer(&ownProvider))
		*(*windowsGUID)(unsafe.Pointer(&native[64])) = layer
		*(*windowsGUID)(unsafe.Pointer(&native[80])) = ownSublayer
		binary.LittleEndian.PutUint32(native[96:], 4)
		*(*uintptr)(unsafe.Pointer(&native[104])) = uintptr(unsafe.Pointer(&weight))
		binary.LittleEndian.PutUint32(native[112:], 1)
		*(*uintptr)(unsafe.Pointer(&native[120])) = uintptr(unsafe.Pointer(&condition[0]))
		binary.LittleEndian.PutUint32(native[128:], 0x1002)
		*(*windowsGUID)(unsafe.Pointer(&condition[0])) = field
		binary.LittleEndian.PutUint32(condition[24:], 4)
		*(*uintptr)(unsafe.Pointer(&condition[32])) = uintptr(unsafe.Pointer(&l.owner.LUID))
		var id uint64
		r, _, _ = fwp.NewProc("FwpmFilterAdd0").Call(engine, uintptr(unsafe.Pointer(&native[0])), 0, uintptr(unsafe.Pointer(&id)))
		runtime.KeepAlive([]any{native, condition, weight, label, l})
		if e := nativeResult("add guardian TUN permission", r); e != nil {
			return e
		}
		filterIDs[strings.ToLower(filter.Key)] = id
	}
	r, _, _ = fwp.NewProc("FwpmTransactionCommit0").Call(engine)
	if e := nativeResult("commit TUN permissions", r); e != nil {
		return e
	}
	committed = true
	l.engine = engine
	l.filters = append([]guardianFilter(nil), filters...)
	l.filterIDs = filterIDs
	l.keys = nil
	for _, filter := range filters {
		l.keys = append(l.keys, strings.ToLower(filter.Key))
	}
	return nil
}

func (l *guardianLease) identityValid() bool {
	index, id, alias, err := interfaceIdentity(l.owner.LUID)
	return err == nil && index == l.owner.Index && formatGUID(id) == l.owner.GUID && strings.EqualFold(alias, l.owner.TunName)
}
func (l *guardianLease) verifiedKeys() []string {
	if l.engine == 0 || !processAlive(l.process) || !l.identityValid() {
		return nil
	}
	if len(l.filters) != 4 || len(l.filterIDs) != 4 {
		return nil
	}
	for _, expected := range l.filters {
		key, _ := parseGUID(expected.Key)
		layer, _ := parseGUID(expected.Layer)
		field, _ := parseGUID("618a9b6d-386b-4136-ad6e-b51587cfb1cd")
		if strings.EqualFold(expected.Layer, "c38d57d1-05a7-4c33-904f-7fbceee60e82") || strings.EqualFold(expected.Layer, "4a72393b-319f-44bc-84c3-ba54dcb3b6b4") {
			field, _ = parseGUID("93ae8f5b-7f6f-4719-98c8-14e97429ef04")
		}
		var filter uintptr
		r, _, _ := fwp.NewProc("FwpmFilterGetByKey0").Call(l.engine, uintptr(unsafe.Pointer(&key)), uintptr(unsafe.Pointer(&filter)))
		if r != 0 || filter == 0 {
			return nil
		}
		valid := *(*uint32)(unsafe.Pointer(filter + 32))&0x21 == 0 && *(*windowsGUID)(unsafe.Pointer(filter + 64)) == layer && *(*uint32)(unsafe.Pointer(filter + 128)) == 0x1002 && *(*uint32)(unsafe.Pointer(filter + 112)) == 1 && *(*uint64)(unsafe.Pointer(filter + 176)) == l.filterIDs[strings.ToLower(expected.Key)]
		provider := *(*uintptr)(unsafe.Pointer(filter + 40))
		condition := *(*uintptr)(unsafe.Pointer(filter + 120))
		weight := *(*uintptr)(unsafe.Pointer(filter + 104))
		valid = valid && provider != 0 && ownsFilter(*(*windowsGUID)(unsafe.Pointer(provider)), *(*windowsGUID)(unsafe.Pointer(filter + 80))) && *(*uint32)(unsafe.Pointer(filter + 96)) == 4 && weight != 0 && *(*uint64)(unsafe.Pointer(weight)) == 900
		if valid && condition != 0 {
			value := *(*uintptr)(unsafe.Pointer(condition + 32))
			valid = *(*windowsGUID)(unsafe.Pointer(condition)) == field && *(*uint32)(unsafe.Pointer(condition + 16)) == 0 && *(*uint32)(unsafe.Pointer(condition + 24)) == 4 && value != 0 && *(*uint64)(unsafe.Pointer(value)) == l.owner.LUID
		} else {
			valid = false
		}
		fwp.NewProc("FwpmFreeMemory0").Call(uintptr(unsafe.Pointer(&filter)))
		if !valid {
			return nil
		}
	}
	return append([]string(nil), l.keys...)
}
func (g *guardian) command(request guardianRequest, caller uint32) guardianResponse {
	g.mu.Lock()
	defer g.mu.Unlock()
	fail := func(e error) guardianResponse { return guardianResponse{Error: e.Error()} }
	if request.Operation == "status" {
		if g.lease != nil {
			if !processAlive(g.lease.process) {
				return guardianResponse{OK: true, Owner: &g.lease.owner}
			} else {
				return guardianResponse{OK: true, Owner: &g.lease.owner, Keys: g.lease.verifiedKeys()}
			}
		}
		return guardianResponse{OK: true}
	}
	if request.Operation == "prepare_owned_tun" {
		if g.lease != nil && !processAlive(g.lease.process) {
			if e := g.lease.release(); e != nil {
				return fail(e)
			}
			g.lease = nil
		}
		if g.lease != nil {
			return fail(errors.New("guardian already owns a TUN; stop it before replacing"))
		}
		process, e := coreProcess(request)
		if e != nil {
			return fail(e)
		}
		lease, e := createAdapter(request, process)
		if e != nil {
			kernel.NewProc("CloseHandle").Call(process)
			return fail(e)
		}
		g.lease = lease
		return guardianResponse{OK: true, Owner: &lease.owner}
	}
	if g.lease == nil {
		if request.Operation == "revoke_owned_tun" || request.Operation == "release_owned_tun" {
			return guardianResponse{OK: true}
		}
		return fail(errors.New("guardian has no prepared native lease"))
	}
	l := g.lease
	if request.PID != l.owner.PID || request.StartedAtMillis != l.owner.StartedAtMillis {
		return fail(errors.New("native lease identity does not match"))
	}
	switch request.Operation {
	case "arm_owned_tun":
		_, id, alias, e := interfaceIdentity(l.owner.LUID)
		if e != nil || !processAlive(l.process) || request.LUID != l.owner.LUID || !strings.EqualFold(request.GUID, l.owner.GUID) || formatGUID(id) != l.owner.GUID || !strings.EqualFold(alias, l.owner.TunName) {
			return fail(errors.New("guardian-owned TUN identity changed"))
		}
		if e = l.arm(request.Filters); e != nil {
			return fail(e)
		}
	case "revoke_owned_tun":
		if e := l.revoke(); e != nil {
			return fail(e)
		}
	case "release_owned_tun":
		if caller != l.owner.PID && processAlive(l.process) {
			return fail(errors.New("running native must finish adapter cleanup before releasing creator"))
		}
		if e := l.release(); e != nil {
			return fail(e)
		}
		g.lease = nil
		return guardianResponse{OK: true}
	default:
		return fail(errors.New("unknown guardian operation"))
	}
	return guardianResponse{OK: true, Owner: &l.owner, Keys: l.verifiedKeys()}
}
func (g *guardian) watch() {
	for {
		select {
		case <-stopped:
			return
		case <-time.After(100 * time.Millisecond):
		}
		g.mu.Lock()
		if g.lease != nil {
			if !processAlive(g.lease.process) {
				if g.lease.release() == nil {
					g.lease = nil
				}
			} else if !g.lease.identityValid() {
				_ = g.lease.revoke() // Never adopt a changed adapter or release a live creator.
			}
		}
		g.mu.Unlock()
	}
}
func (g *guardian) shutdown() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.lease == nil {
		return nil
	}
	l := g.lease
	if e := l.revoke(); e != nil {
		return e
	}
	if processAlive(l.process) {
		ok, _, e := kernel.NewProc("TerminateProcess").Call(l.process, 0)
		if ok == 0 {
			return e
		}
		result, _, _ := kernel.NewProc("WaitForSingleObject").Call(l.process, 10000)
		if result != 0 {
			return errors.New("pinned native did not terminate")
		}
	}
	if e := l.release(); e != nil {
		return e
	}
	g.lease = nil
	return nil
}

type securityAttributes struct {
	length     uint32
	descriptor uintptr
	inherit    uint32
}

type pipeReader struct{ handle uintptr }

func (p pipeReader) Read(data []byte) (int, error) {
	if len(data) == 0 {
		return 0, nil
	}
	var count uint32
	ok, _, err := kernel.NewProc("ReadFile").Call(p.handle, uintptr(unsafe.Pointer(&data[0])), uintptr(len(data)), uintptr(unsafe.Pointer(&count)), 0)
	if ok == 0 {
		if err == syscall.Errno(109) {
			return 0, io.EOF
		}
		return int(count), err
	}
	if count == 0 {
		return 0, io.EOF
	}
	return int(count), nil
}

func createControlPipe() (uintptr, error) {
	sddl, _ := syscall.UTF16PtrFromString("D:P(A;;GA;;;SY)(A;;GA;;;BA)")
	var descriptor uintptr
	ok, _, e := advapi.NewProc("ConvertStringSecurityDescriptorToSecurityDescriptorW").Call(uintptr(unsafe.Pointer(sddl)), 1, uintptr(unsafe.Pointer(&descriptor)), 0)
	if ok == 0 {
		return 0, e
	}
	defer kernel.NewProc("LocalFree").Call(descriptor)
	attributes := securityAttributes{length: uint32(unsafe.Sizeof(securityAttributes{})), descriptor: descriptor}
	name, _ := syscall.UTF16PtrFromString(controlPipe)
	pipe, _, e := kernel.NewProc("CreateNamedPipeW").Call(uintptr(unsafe.Pointer(name)), 3|0x80000, 8, 1, 8192, 8192, 5000, uintptr(unsafe.Pointer(&attributes)))
	if pipe == ^uintptr(0) {
		return 0, e
	}
	return pipe, nil
}
func (g *guardian) serve(pipe uintptr) {
	defer kernel.NewProc("CloseHandle").Call(pipe)
	for {
		connected, _, e := kernel.NewProc("ConnectNamedPipe").Call(pipe, 0)
		if connected == 0 && e != syscall.Errno(535) {
			return
		}
		var caller uint32
		ok, _, _ := kernel.NewProc("GetNamedPipeClientProcessId").Call(pipe, uintptr(unsafe.Pointer(&caller)))
		response := guardianResponse{Error: "guardian client identity unavailable"}
		if ok != 0 {
			line, err := bufio.NewReader(io.LimitReader(pipeReader{pipe}, 8193)).ReadBytes('\n')
			if err == nil && len(line) <= 8192 {
				var request guardianRequest
				decoder := json.NewDecoder(bytes.NewReader(line))
				decoder.DisallowUnknownFields()
				if e := decoder.Decode(&request); e == nil {
					response = g.command(request, caller)
				} else {
					response.Error = "invalid guardian request"
				}
			}
			data, _ := json.Marshal(response)
			data = append(data, '\n')
			var n uint32
			kernel.NewProc("WriteFile").Call(pipe, uintptr(unsafe.Pointer(&data[0])), uintptr(len(data)), uintptr(unsafe.Pointer(&n)), 0)
		}
		kernel.NewProc("FlushFileBuffers").Call(pipe)
		kernel.NewProc("DisconnectNamedPipe").Call(pipe)
		select {
		case <-stopped:
			return
		default:
		}
	}
}
func guardianClient(encoded string) error {
	data, e := base64.StdEncoding.DecodeString(encoded)
	if e != nil || len(data) > 8191 {
		return errors.New("invalid control request")
	}
	var request guardianRequest
	if e = json.Unmarshal(data, &request); e != nil {
		return e
	}
	data = append(data, '\n')
	name, _ := syscall.UTF16PtrFromString(controlPipe)
	kernel.NewProc("WaitNamedPipeW").Call(uintptr(unsafe.Pointer(name)), 5000)
	pipe, _, e := kernel.NewProc("CreateFileW").Call(uintptr(unsafe.Pointer(name)), 0x80000000|0x40000000, 0, 0, 3, 0, 0)
	if pipe == ^uintptr(0) {
		return e
	}
	defer kernel.NewProc("CloseHandle").Call(pipe)
	var written uint32
	ok, _, e := kernel.NewProc("WriteFile").Call(pipe, uintptr(unsafe.Pointer(&data[0])), uintptr(len(data)), uintptr(unsafe.Pointer(&written)), 0)
	if ok == 0 || written != uint32(len(data)) {
		return e
	}
	line, e := bufio.NewReader(io.LimitReader(pipeReader{pipe}, 8193)).ReadBytes('\n')
	if e != nil || len(line) > 8192 {
		return errors.New("guardian response unavailable")
	}
	_, e = os.Stdout.Write(line)
	return e
}
