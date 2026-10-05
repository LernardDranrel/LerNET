package expert

import (
	"net/netip"
	"testing"
)

func TestLerNETRouteCaptureIncludesRemotePPPResourcePrefix(t *testing.T) {
	// The local PPP address /32 cannot describe the remote resources /16.
	route := captureRoute{prefix: netip.MustParsePrefix("10.100.0.0/16"), cost: 25}
	children, err := captureRoutePrefixes(route)
	if err != nil || len(children) != 2 || children[0].Bits() != 17 || children[1].Bits() != 17 {
		t.Fatalf("PPP resources not captured %v %v", children, err)
	}
	table := []captureRoute{route}
	for _, child := range children {
		table = append(table, captureRoute{prefix: child, owned: true})
	}
	if err := verifyRouteCapture(table); err != nil {
		t.Fatal(err)
	}
	if err := verifyRouteCapture(table[:2]); err == nil {
		t.Fatal("half a corporate prefix was accepted as full capture")
	}
}

func TestLerNETRouteCaptureUsesStrictHostMetricAndActualZeroReadback(t *testing.T) {
	for _, prefix := range []string{"203.0.113.7/32", "2001:db8::7/128"} {
		foreign := captureRoute{prefix: netip.MustParsePrefix(prefix), cost: 20}
		own := captureRoute{prefix: foreign.prefix, owned: true}
		if err := verifyRouteCapture([]captureRoute{foreign, own}); err != nil {
			t.Fatal(err)
		}
		own.cost = 1
		if err := verifyRouteCapture([]captureRoute{foreign, own}); err == nil {
			t.Fatal("native zero metric was not read back")
		}
		own.cost = 0
		foreign.cost = 0
		if _, err := captureRoutePrefixes(foreign); err == nil {
			t.Fatal("foreign host-route metric tie silently accepted")
		}
		if err := verifyRouteCapture([]captureRoute{foreign, own}); err == nil {
			t.Fatal("owned host tie silently accepted")
		}
	}
}

func TestLerNETRouteCaptureDetectsLateMoreSpecificForeignRoute(t *testing.T) {
	foreign := captureRoute{prefix: netip.MustParsePrefix("0.0.0.0/0"), cost: 25}
	table := []captureRoute{foreign, {prefix: netip.MustParsePrefix("0.0.0.0/1"), owned: true}, {prefix: netip.MustParsePrefix("128.0.0.0/1"), owned: true}}
	if err := verifyRouteCapture(table); err != nil {
		t.Fatal(err)
	}
	table = append(table, captureRoute{prefix: netip.MustParsePrefix("10.100.0.0/16"), cost: 20})
	if err := verifyRouteCapture(table); err == nil {
		t.Fatal("late corporate prefix bypass was accepted")
	}
	for _, child := range splitCapturePrefix(netip.MustParsePrefix("10.100.0.0/16")) {
		table = append(table, captureRoute{prefix: child, owned: true})
	}
	if err := verifyRouteCapture(table); err != nil {
		t.Fatal(err)
	}
}

func TestLerNETRouteCaptureSplitsForeignVPNDefaultHalves(t *testing.T) {
	for _, prefix := range []string{"0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1"} {
		foreign := captureRoute{prefix: netip.MustParsePrefix(prefix)}
		children, err := captureRoutePrefixes(foreign)
		if err != nil || len(children) != 2 || children[0].Bits() != 2 || children[1].Bits() != 2 {
			t.Fatalf("split %s %v %v", prefix, children, err)
		}
		table := []captureRoute{foreign, {prefix: children[0], owned: true}, {prefix: children[1], owned: true}}
		if err := verifyRouteCapture(table); err != nil {
			t.Fatal(err)
		}
	}
}
