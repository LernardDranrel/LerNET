package tun

import (
	"errors"
	"reflect"
	"testing"
)

func TestLerNETGuardedCloseRevokesBeforeCleanupAndReleasesAfter(t *testing.T) {
	for _, failure := range []string{"", "revoke", "native", "release"} {
		t.Run(failure, func(t *testing.T) {
			var stages []string
			sentinel := errors.New("failed")
			stage := func(name string) error {
				stages = append(stages, name)
				if name == failure {
					return sentinel
				}
				return nil
			}
			guard := &LerNETGuardedAdapter{BeforeClose: func() error { return stage("revoke") }, AfterClose: func() error { return stage("release") }}
			err := lernetCloseInOrder(guard, func() error { return stage("native") })
			want := []string{"revoke", "native", "release"}
			if failure == "revoke" {
				want = want[:1]
			}
			if failure == "native" {
				want = want[:2]
			}
			if !reflect.DeepEqual(stages, want) {
				t.Fatalf("order %v != %v", stages, want)
			}
			if failure != "" && err != sentinel || failure == "" && err != nil {
				t.Fatalf("close result %v", err)
			}
		})
	}
	called := false
	if err := lernetCloseInOrder(nil, func() error { called = true; return nil }); err != nil || !called {
		t.Fatal("guard-off native cleanup was skipped")
	}
}
