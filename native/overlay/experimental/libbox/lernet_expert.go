package libbox

import (
	"context"
	"errors"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/lernet/expert"
	"github.com/sagernet/sing/service"
)

// ExpertSession is separate from StartedService: policy publication never
// calls StartOrReloadService and never closes the ingress's native interface.
type ExpertSession struct{ session *expert.Session }

func ExpertCapabilities() string { return expert.Capabilities() }

func NewExpertSession(ingressJSON string, platform PlatformInterface) (*ExpertSession, error) {
	if platform == nil {
		return nil, errors.New("missing_platform_interface")
	}
	ctx := baseContext(platform)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, &platformInterfaceWrapper{iif: platform, useProcFS: platform.UseProcFS()})
	session, err := expert.New(ctx, ingressJSON)
	if err != nil {
		return nil, err
	}
	return &ExpertSession{session: session}, nil
}

func (s *ExpertSession) Start(revision int64, policyJSON, manifestJSON string) (string, error) {
	ack, err := s.session.Start(context.Background(), revision, policyJSON, manifestJSON)
	if err != nil {
		return "", errors.New(expert.ErrorCode(err))
	}
	return expert.Encode(ack), nil
}
func (s *ExpertSession) Apply(instanceID, interfaceID string, expectedRevision, nextRevision int64, policyJSON, manifestJSON string) (string, error) {
	ack, err := s.session.Apply(context.Background(), instanceID, interfaceID, expectedRevision, nextRevision, policyJSON, manifestJSON)
	if err != nil {
		return "", errors.New(expert.ErrorCode(err))
	}
	return expert.Encode(ack), nil
}
func (s *ExpertSession) Close() error {
	if err := s.session.Close(); err != nil {
		return errors.New(expert.ErrorCode(err))
	}
	return nil
}
func (s *ExpertSession) Status() string  { return s.session.Status() }
func (s *ExpertSession) NetworkChanged() { s.session.NetworkChanged() }
func (s *ExpertSession) WakeExit(tag string) error {
	if err := s.session.WakeExit(context.Background(), tag); err != nil {
		return errors.New(expert.ErrorCode(err))
	}
	return nil
}
func (s *ExpertSession) SleepExit(tag string) error {
	if err := s.session.SleepExit(tag); err != nil {
		return errors.New(expert.ErrorCode(err))
	}
	return nil
}
func (s *ExpertSession) RecoverExit(tag string) error {
	if err := s.session.RecoverExit(context.Background(), tag); err != nil {
		return errors.New(expert.ErrorCode(err))
	}
	return nil
}
func (s *ExpertSession) ProbeExit(tag, url string, timeoutMs int64) string {
	return expert.Encode(s.session.ProbeExit(context.Background(), tag, url, timeoutMs))
}
func (s *ExpertSession) WakeExitAt(instance, interfaceID string, revision int64, tag string) error {
	if err := s.session.WakeExitAt(context.Background(), instance, interfaceID, revision, tag); err != nil {
		return errors.New(expert.ErrorCode(err))
	}
	return nil
}
func (s *ExpertSession) SleepExitAt(instance, interfaceID string, revision int64, tag string) error {
	if err := s.session.SleepExitAt(instance, interfaceID, revision, tag); err != nil {
		return errors.New(expert.ErrorCode(err))
	}
	return nil
}
func (s *ExpertSession) RecoverExitAt(instance, interfaceID string, revision int64, tag string) error {
	if err := s.session.RecoverExitAt(context.Background(), instance, interfaceID, revision, tag); err != nil {
		return errors.New(expert.ErrorCode(err))
	}
	return nil
}
func (s *ExpertSession) ProbeExitAt(instance, interfaceID string, revision int64, tag, url string, timeoutMs int64) (string, error) {
	result, err := s.session.ProbeExitAt(context.Background(), instance, interfaceID, revision, tag, url, timeoutMs)
	if err != nil {
		return "", errors.New(expert.ErrorCode(err))
	}
	return expert.Encode(result), nil
}
