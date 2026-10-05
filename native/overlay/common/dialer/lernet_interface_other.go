//go:build !windows

package dialer

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/option"
)

func LerNETInterfaceContext(ctx context.Context, binding option.LerNETInterfaceOptions) (context.Context, error) {
	return nil, errors.New("interface_binding_platform_unsupported")
}
