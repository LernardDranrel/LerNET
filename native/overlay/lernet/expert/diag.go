package expert

import (
	"fmt"
	"strings"

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

func watchState(refresh, stop, captureBroken bool) string {
	state := ""
	if refresh {
		state += "refresh"
	}
	if stop {
		state += "stop"
	}
	if captureBroken {
		state += "capture"
	}
	if state == "" {
		return "idle"
	}
	return state
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
	emitDiag("fallback", "edge", "outcome="+outcome, "code="+code)
}
