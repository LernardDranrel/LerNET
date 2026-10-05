//go:build !windows

package dialer

import (
	"context"
	"errors"
)

func LerNETSystemRouteContext(ctx context.Context) (context.Context, error) {
	return nil, errors.New("system_route_platform_unsupported")
}
