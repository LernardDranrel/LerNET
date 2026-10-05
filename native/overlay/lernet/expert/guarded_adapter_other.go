//go:build !windows

package expert

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
)

func guardedAdapterContext(ctx context.Context, adapter *tun.LerNETGuardedAdapter) (context.Context, error) {
	if adapter != nil {
		return nil, errors.New("guarded_adapter_platform_unsupported")
	}
	return ctx, nil
}
func validateGuardedIngress(context.Context, *option.Options) error { return nil }
func finalizeGuardedAdapter(context.Context) error                  { return nil }
