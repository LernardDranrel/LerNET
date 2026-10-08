package adapter

// A validated policy proves that this DNS name can only use unprotected Direct.
// The selected resolver is checked too; proxy/protected DNS stays unchanged.
type LerNETDirectIPv6Policy interface {
	UnavailableIPv6(domain, server string) bool
}
