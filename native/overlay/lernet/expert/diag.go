package expert

import (
	"fmt"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/adapter"
)

// emitDiag prints one fact. Privacy, level and repeat suppression stay in the Kotlin journal:
// this process cannot call that code, so the line carries only tokens.
func emitDiag(event, kind string, fields ...string) {
	var b strings.Builder
	b.WriteString("LERNET_DIAG ev=")
	b.WriteString(diagToken(event))
	b.WriteString(" kind=")
	b.WriteString(diagToken(kind))
	for _, field := range fields {
		key, value, ok := strings.Cut(field, "=")
		if !ok {
			continue
		}
		b.WriteByte(' ')
		b.WriteString(diagToken(key))
		b.WriteByte('=')
		b.WriteString(diagToken(value))
	}
	b.WriteByte('\n')
	fmt.Print(b.String())
}

func diagToken(value string) string {
	if value == "" {
		return "-"
	}
	for _, r := range value {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '_', r == '-', r == '.', r == ':':
		default:
			return "unlisted"
		}
	}
	return value
}

func itoa(value int) string {
	return itoa64(int64(value))
}

func itoa64(value int64) string {
	if value == 0 {
		return "0"
	}
	negative := value < 0
	if negative {
		value = -value
	}
	var digits [20]byte
	i := len(digits)
	for value > 0 {
		i--
		digits[i] = byte('0' + value%10)
		value /= 10
	}
	if negative {
		i--
		digits[i] = '-'
	}
	return string(digits[i:])
}

var (
	fallbackMu          sync.Mutex
	fallbackLastOutcome string
	fallbackLastCode    string
)

// fallbackKind is edge on the first decision and on every change.
// The same outcome and code again is state, so the journal can count copies.
func fallbackKind(previousOutcome, previousCode, outcome, code string) string {
	if previousOutcome == outcome && previousCode == code && previousOutcome != "" {
		return "state"
	}
	return "edge"
}

func emitFallback(protected bool, out adapter.Outbound) {
	outcome := "block"
	if out != nil {
		outcome = "direct"
	}
	code := "open"
	if protected {
		code = "protected"
	}
	fallbackMu.Lock()
	kind := fallbackKind(fallbackLastOutcome, fallbackLastCode, outcome, code)
	fallbackLastOutcome, fallbackLastCode = outcome, code
	fallbackMu.Unlock()
	emitDiag("fallback", kind, "outcome="+outcome, "code="+code)
}
