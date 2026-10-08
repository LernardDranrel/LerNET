package tun

import (
	"errors"
	"net/netip"
	"syscall"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// Session serializes calls. Address-created routes and unrelated rows are retained.
func (t *NativeTun) LerNETUpdateCaptureRouteOptions(options Options) error {
	failed := func(stage string, err error) error {
		var code syscall.Errno
		errors.As(err, &code)
		return &LerNETCaptureRouteUpdateError{Stage: stage, Code: uint32(code)}
	}
	if !options.AutoRoute || options.EXP_ExternalConfiguration {
		return failed("configuration", nil)
	}
	previous, err := t.options.BuildAutoRouteRanges(false)
	if err != nil {
		return failed("previous_ranges", err)
	}
	next, err := options.BuildAutoRouteRanges(false)
	if err != nil {
		return failed("next_ranges", err)
	}
	luid := winipcfg.LUID(t.adapter.LUID())
	gateway := func(prefix netip.Prefix) netip.Addr {
		if prefix.Addr().Is4() {
			return options.Inet4GatewayAddr()
		}
		return options.Inet6GatewayAddr()
	}
	err = lernetUpdateCaptureSet(previous, next, func(prefix netip.Prefix) error {
		hop := gateway(prefix)
		row, err := luid.Route(prefix, hop)
		if err == nil {
			if row.Metric == 0 {
				return nil
			}
			return failed("existing_metric", nil)
		}
		if !errors.Is(err, windows.ERROR_NOT_FOUND) {
			return failed("read_route", err)
		}
		if err = luid.AddRoute(prefix, hop, 0); err != nil {
			return failed("add_route", err)
		}
		return nil
	}, func(prefix netip.Prefix) error {
		hop := gateway(prefix)
		row, err := luid.Route(prefix, hop)
		if errors.Is(err, windows.ERROR_NOT_FOUND) {
			return nil
		}
		if err != nil {
			return failed("read_obsolete_route", err)
		}
		if row.Metric != 0 {
			return failed("obsolete_metric", nil)
		}
		if err = luid.DeleteRoute(prefix, hop); err != nil && !errors.Is(err, windows.ERROR_NOT_FOUND) {
			return failed("delete_route", err)
		}
		return nil
	})
	if err != nil {
		return err
	}
	t.options = options
	return nil
}
