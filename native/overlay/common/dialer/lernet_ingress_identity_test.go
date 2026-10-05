package dialer

import "testing"

func TestLerNETIngressIdentityDoesNotAdoptReusedIndexOrAlias(t *testing.T) {
	own := &LerNETIngressIdentity{LUID: 42, Index: 7, GUID: "own"}
	if !own.Matches(42, 7, "own") {
		t.Fatal("actual ownership lost")
	}
	for _, other := range []*LerNETIngressIdentity{{LUID: 43, Index: 7, GUID: "other"}, {LUID: 42, Index: 7, GUID: "other"}, {LUID: 42, Index: 8, GUID: "own"}} {
		if own.Matches(other.LUID, other.Index, other.GUID) {
			t.Fatal("replacement interface treated as owned")
		}
	}
	holder := &LerNETIngressIdentityHolder{}
	if holder.Load().Matches(42, 7, "own") {
		t.Fatal("unknown identity treated as owned")
	}
	holder.Store(own)
	if !holder.Load().Matches(42, 7, "own") {
		t.Fatal("actual identity not retained")
	}
}
