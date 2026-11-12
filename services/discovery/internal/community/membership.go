package community

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"time"

	datastore "github.com/ipfs/go-datastore"
)

const (
	invitationPrefix = "/relyra/community/invitation/"
	memberPrefix = "/relyra/community/member/"
	invitationDomain = "RELYRA-COMMUNITY-INVITATION-V1"
	acceptanceDomain = "RELYRA-COMMUNITY-ACCEPTANCE-V1"
	accessDomain = "RELYRA-COMMUNITY-ACCESS-V1"
	MaxInvitationBytes = 8 * 1024
)

type Invitation struct {
	Version int `json:"version"`
	InviteID string `json:"inviteId"`
	CommunityID string `json:"communityId"`
	OwnerPublicID string `json:"ownerPublicId"`
	MemberPublicID string `json:"memberPublicId"`
	PolicyVersion uint64 `json:"policyVersion"`
	TokenHash string `json:"tokenHash"`
	CreatedAt int64 `json:"createdAt"`
	ExpiresAt int64 `json:"expiresAt"`
	Signature string `json:"signature"`
}

type InvitationAcceptance struct {
	Version int `json:"version"`
	InviteID string `json:"inviteId"`
	CommunityID string `json:"communityId"`
	MemberPublicID string `json:"memberPublicId"`
	AcceptedAt int64 `json:"acceptedAt"`
	Signature string `json:"signature"`
}

type Membership struct {
	CommunityID string `json:"communityId"`
	MemberPublicID string `json:"memberPublicId"`
	InviteID string `json:"inviteId"`
	JoinedAt int64 `json:"joinedAt"`
	PolicyVersion uint64 `json:"policyVersion"`
}

func invitationKey(communityID, inviteID string) datastore.Key {
	return datastore.NewKey(invitationPrefix + strings.ToLower(communityID) + "/" + strings.ToLower(inviteID))
}
func membershipKey(communityID, memberPublicID string) datastore.Key {
	return datastore.NewKey(memberPrefix + strings.ToLower(communityID) + "/" + strings.ToLower(memberPublicID))
}
func invitationTokenHash(token string) (string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(raw) != 32 { return "", errors.New("invitation token must be 32 random bytes") }
	sum := sha256.Sum256(raw)
	return base64.RawURLEncoding.EncodeToString(sum[:]), nil
}
func CanonicalInvitation(i Invitation) ([]byte, error) {
	if i.Version != ManifestVersion || !validCommunityID(i.CommunityID) ||
		!communityIDPattern.MatchString(strings.ToLower(i.InviteID)) {
		return nil, errors.New("invalid invitation ID or version")
	}
	if _, err := identityKey(i.OwnerPublicID); err != nil { return nil, err }
	if _, err := identityKey(i.MemberPublicID); err != nil { return nil, err }
	if i.PolicyVersion == 0 || i.CreatedAt <= 0 || i.ExpiresAt <= i.CreatedAt ||
		i.ExpiresAt-i.CreatedAt > 14*24*60*60*1000 {
		return nil, errors.New("invalid invitation policy or expiration")
	}
	if len(i.TokenHash) < 40 || len(i.TokenHash) > 64 { return nil, errors.New("invalid invitation token hash") }
	return []byte(strings.Join([]string{
		invitationDomain, strings.ToLower(i.InviteID), strings.ToLower(i.CommunityID),
		strings.ToLower(i.OwnerPublicID), strings.ToLower(i.MemberPublicID),
		strconv.FormatUint(i.PolicyVersion, 10), i.TokenHash,
		strconv.FormatInt(i.CreatedAt, 10), strconv.FormatInt(i.ExpiresAt, 10),
	}, "\n")), nil
}
func CanonicalInvitationAcceptance(a InvitationAcceptance, tokenHash string) ([]byte, error) {
	if a.Version != ManifestVersion || !validCommunityID(a.CommunityID) ||
		!communityIDPattern.MatchString(strings.ToLower(a.InviteID)) || a.AcceptedAt <= 0 {
		return nil, errors.New("invalid invitation acceptance")
	}
	if _, err := identityKey(a.MemberPublicID); err != nil { return nil, err }
	return []byte(strings.Join([]string{
		acceptanceDomain, strings.ToLower(a.InviteID), strings.ToLower(a.CommunityID),
		strings.ToLower(a.MemberPublicID), tokenHash, strconv.FormatInt(a.AcceptedAt, 10),
	}, "\n")), nil
}

// CreateInvitation stores only the token hash. The owner sends the signed invitation
// and the bearer token to the named member over RELYRA's end-to-end encrypted chat.
func (s *Store) CreateInvitation(ctx context.Context, raw []byte, token string) (Invitation, error) {
	var inv Invitation
	if len(raw) == 0 || len(raw) > MaxInvitationBytes { return inv, errors.New("invitation is oversized") }
	if err := decodeStrict(raw, &inv); err != nil { return inv, errors.New("invalid invitation JSON") }
	hash, err := invitationTokenHash(token)
	if err != nil { return inv, err }
	if hash != inv.TokenHash { return inv, errors.New("invitation token hash mismatch") }
	payload, err := CanonicalInvitation(inv)
	if err != nil { return inv, err }
	manifest, err := s.GetManifest(ctx, inv.CommunityID, true)
	if err != nil { return inv, err }
	if manifest.Visibility != "private" ||
		!strings.EqualFold(manifest.OwnerPublicID, inv.OwnerPublicID) ||
		manifest.PolicyVersion != inv.PolicyVersion {
		return inv, errors.New("invitation does not match the current private community policy")
	}
	now := s.clock()
	if time.UnixMilli(inv.CreatedAt).After(now.Add(5*time.Minute)) ||
		!time.UnixMilli(inv.ExpiresAt).After(now) {
		return inv, errors.New("invitation is expired or not yet valid")
	}
	publicKey, _ := identityKey(inv.OwnerPublicID)
	signature, err := base64.RawURLEncoding.DecodeString(inv.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize || !ed25519.Verify(publicKey, payload, signature) {
		return inv, errors.New("invalid invitation owner signature")
	}
	if strings.EqualFold(inv.OwnerPublicID, inv.MemberPublicID) { return inv, errors.New("owner is already a community member") }

	s.mu.Lock()
	defer s.mu.Unlock()
	key := invitationKey(inv.CommunityID, inv.InviteID)
	if exists, err := s.ds.Has(ctx, key); err != nil { return inv, err } else if exists {
		return inv, errors.New("invitation ID already exists")
	}
	encoded, err := json.Marshal(inv)
	if err != nil { return inv, err }
	if err := s.ds.Put(ctx, key, encoded); err != nil { return inv, err }
	return inv, nil
}

func (s *Store) AcceptInvitation(ctx context.Context, raw []byte, token string, acceptance InvitationAcceptance) (Membership, error) {
	var membership Membership
	var inv Invitation
	if len(raw) == 0 || len(raw) > MaxInvitationBytes { return membership, errors.New("invitation is oversized") }
	if err := decodeStrict(raw, &inv); err != nil { return membership, errors.New("invalid invitation JSON") }
	hash, err := invitationTokenHash(token)
	if err != nil || hash != inv.TokenHash { return membership, errors.New("invalid invitation token") }
	payload, err := CanonicalInvitation(inv)
	if err != nil { return membership, err }
	ownerKey, _ := identityKey(inv.OwnerPublicID)
	ownerSignature, err := base64.RawURLEncoding.DecodeString(inv.Signature)
	if err != nil || len(ownerSignature) != ed25519.SignatureSize || !ed25519.Verify(ownerKey, payload, ownerSignature) {
		return membership, errors.New("invalid invitation owner signature")
	}
	if !strings.EqualFold(acceptance.InviteID, inv.InviteID) ||
		!strings.EqualFold(acceptance.CommunityID, inv.CommunityID) ||
		!strings.EqualFold(acceptance.MemberPublicID, inv.MemberPublicID) {
		return membership, errors.New("acceptance does not match the invitation recipient")
	}
	acceptPayload, err := CanonicalInvitationAcceptance(acceptance, inv.TokenHash)
	if err != nil { return membership, err }
	memberKey, _ := identityKey(acceptance.MemberPublicID)
	memberSignature, err := base64.RawURLEncoding.DecodeString(acceptance.Signature)
	if err != nil || len(memberSignature) != ed25519.SignatureSize ||
		!ed25519.Verify(memberKey, acceptPayload, memberSignature) {
		return membership, errors.New("invalid member acceptance signature")
	}
	now := s.clock()
	if !time.UnixMilli(inv.ExpiresAt).After(now) ||
		time.UnixMilli(acceptance.AcceptedAt).After(now.Add(5*time.Minute)) ||
		time.UnixMilli(acceptance.AcceptedAt).Before(time.UnixMilli(inv.CreatedAt)) {
		return membership, errors.New("invitation acceptance is expired or invalid")
	}
	manifest, err := s.GetManifest(ctx, inv.CommunityID, true)
	if err != nil { return membership, err }
	if manifest.Visibility != "private" ||
		!strings.EqualFold(manifest.OwnerPublicID, inv.OwnerPublicID) ||
		manifest.PolicyVersion != inv.PolicyVersion {
		return membership, errors.New("invitation was revoked by a policy change")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	storedRaw, err := s.ds.Get(ctx, invitationKey(inv.CommunityID, inv.InviteID))
	if err != nil { return membership, errors.New("invitation is not registered with this service") }
	var stored Invitation
	if json.Unmarshal(storedRaw, &stored) != nil || stored.TokenHash != hash ||
		!strings.EqualFold(stored.MemberPublicID, acceptance.MemberPublicID) {
		return membership, errors.New("stored invitation does not match")
	}
	membership = Membership{
		CommunityID: inv.CommunityID, MemberPublicID: acceptance.MemberPublicID,
		InviteID: inv.InviteID, JoinedAt: acceptance.AcceptedAt, PolicyVersion: inv.PolicyVersion,
	}
	encoded, err := json.Marshal(membership)
	if err != nil { return membership, err }
	if err := s.ds.Put(ctx, membershipKey(inv.CommunityID, acceptance.MemberPublicID), encoded); err != nil {
		return membership, err
	}
	// Redeem the invitation exactly once. The membership record is durable before
	// deleting the one-time invitation, so a successful acceptance cannot be replayed.
	if err := s.ds.Delete(ctx, invitationKey(inv.CommunityID, inv.InviteID)); err != nil && !errors.Is(err, datastore.ErrNotFound) {
		return membership, err
	}
	return membership, nil
}

func (s *Store) CanReadPrivate(ctx context.Context, communityID, memberPublicID string) bool {
	manifest, err := s.GetManifest(ctx, communityID, true)
	if err != nil { return false }
	if manifest.Visibility == "public" || strings.EqualFold(manifest.OwnerPublicID, memberPublicID) { return true }
	for _, moderator := range manifest.ModeratorPublicIDs {
		if strings.EqualFold(moderator, memberPublicID) { return true }
	}
	_, err = s.ds.Get(ctx, membershipKey(communityID, memberPublicID))
	return err == nil
}

func (s *Store) GetManifestForMember(ctx context.Context, communityID, memberPublicID string) (Manifest, error) {
	if !s.CanReadPrivate(ctx, communityID, memberPublicID) { return Manifest{}, datastore.ErrNotFound }
	return s.GetManifest(ctx, communityID, true)
}

func (s *Store) EventsForMember(ctx context.Context, communityID, memberPublicID string, limit int) ([]ModerationEvent, error) {
	if !s.CanReadPrivate(ctx, communityID, memberPublicID) { return nil, datastore.ErrNotFound }
	return s.eventsUnchecked(ctx, communityID, limit)
}

func canonicalAccess(method, path, memberPublicID string, timestamp int64) ([]byte, error) {
	if _, err := identityKey(memberPublicID); err != nil { return nil, err }
	if method == "" || path == "" || timestamp <= 0 { return nil, errors.New("invalid private community access proof") }
	return []byte(strings.Join([]string{
		accessDomain, strings.ToUpper(method), path, strings.ToLower(memberPublicID), strconv.FormatInt(timestamp, 10),
	}, "\n")), nil
}

func VerifyAccessProof(method, path, memberPublicID string, timestamp int64, signatureText string, now time.Time) error {
	issued := time.UnixMilli(timestamp)
	if issued.After(now.Add(5*time.Minute)) || now.Sub(issued) > 5*time.Minute { return errors.New("private community access proof expired") }
	payload, err := canonicalAccess(method, path, memberPublicID, timestamp)
	if err != nil { return err }
	publicKey, _ := identityKey(memberPublicID)
	signature, err := base64.RawURLEncoding.DecodeString(signatureText)
	if err != nil || len(signature) != ed25519.SignatureSize || !ed25519.Verify(publicKey, payload, signature) {
		return errors.New("invalid private community access signature")
	}
	return nil
}
