//go:build !windows

package expert

import (
	"context"
	"github.com/sagernet/sing-box/option"
)

func requireExpertPrivilege() error { return nil }

func preparePlatformIngress(context.Context, *option.Options) error { return nil }
func (s *Session) initializePlatformIdentity() error                { return nil }
func (s *Session) verifyPlatformCapture(string) error               { return nil }
func (s *Session) refreshPlatformUnderlay(name string) error        { return nil }
func (s *Session) startPlatformNetworkWatch()                       {}
