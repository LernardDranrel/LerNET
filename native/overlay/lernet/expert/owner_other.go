//go:build !windows

package expert

import "context"

func WatchOwner(context.Context, context.CancelFunc, uint32, int64) (func(), error) {
	return func() {}, nil
}
