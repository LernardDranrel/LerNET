package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/lernet/expert"
	"github.com/spf13/cobra"
)

func init() {
	var listen, tokenFile string
	var ownerPID uint32
	var ownerStartedMs int64
	command := &cobra.Command{Use: "expert", Short: "Serve LerNET's authenticated persistent-ingress control API", RunE: func(*cobra.Command, []string) error {
		secret, err := os.ReadFile(tokenFile)
		if err != nil {
			return fmt.Errorf("control_token_file_unavailable")
		}
		ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer cancel()
		stopOwner, err := expert.WatchOwner(ctx, cancel, ownerPID, ownerStartedMs)
		if err != nil {
			return err
		}
		defer stopOwner()
		// Even an uncooperative native provider cannot keep the trusted child
		// and its TUN alive indefinitely after the desktop owner dies.
		done := make(chan struct{})
		defer close(done)
		go func() {
			select {
			case <-ctx.Done():
			case <-done:
				return
			}
			timer := time.NewTimer(5 * time.Second)
			defer timer.Stop()
			select {
			case <-timer.C:
				os.Exit(1)
			case <-done:
			}
		}()
		return expert.ListenControl(include.Context(ctx), listen, strings.TrimSpace(string(secret)), func(port int) { fmt.Printf("LERNET_CONTROL_READY {\"port\":%d,\"protocol_version\":1}\n", port) })
	}}
	command.Flags().StringVar(&listen, "listen", "127.0.0.1:0", "Loopback control address")
	command.Flags().StringVar(&tokenFile, "token-file", "", "Private file containing a random control token")
	command.Flags().Uint32Var(&ownerPID, "owner-pid", 0, "Desktop owner process ID (required on Windows)")
	command.Flags().Int64Var(&ownerStartedMs, "owner-started-ms", 0, "Desktop process creation time in Unix milliseconds (required on Windows)")
	mainCommand.AddCommand(command)
}
