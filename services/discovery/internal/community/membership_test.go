package community

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"testing"
	"time"

	datastore "github.com/ipfs/go-datastore"
	"github.com/ipfs/go-datastore/sync"

)

func membershipIdentity(t *testing.T) testIdentity {
	return makeIdentity(t)
}
func TestPrivateInvitationRequiresOwnerAndMemberSignatures(t *testing.T) {
	ctx := context.Background()
	now := time.Unix(1_800_000_000, 0)
	store := NewStoreWithClock(sync.MutexWrap(datastore.NewMapDatastore()), func() time.Time { return now })
	owner := membershipIdentity(t)
	member := membershipIdentity(t)
	stranger := membershipIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "private", nil, now)
	if _, err := store.PublishManifest(ctx, mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	if store.CanReadPrivate(ctx, manifest.CommunityID, member.id) {
		t.Fatal("non-member could read private community before invitation acceptance")
	}

	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil { t.Fatal(err) }
	token := base64.RawURLEncoding.EncodeToString(tokenBytes)
	digest, _ := invitationTokenHash(token)
	invitation := Invitation{
		Version: ManifestVersion,
		InviteID: "b1b2c3d4-e5f6-4789-8abc-0123456789ab",
		CommunityID: manifest.CommunityID,
		OwnerPublicID: owner.id,
		MemberPublicID: member.id,
		PolicyVersion: manifest.PolicyVersion,
		TokenHash: digest,
		CreatedAt: now.UnixMilli(),
		ExpiresAt: now.Add(24*time.Hour).UnixMilli(),
	}
	invitationPayload, err := CanonicalInvitation(invitation)
	if err != nil { t.Fatal(err) }
	invitation.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(owner.private, invitationPayload))
	rawInvitation := mustJSON(t, invitation)
	if _, err := store.CreateInvitation(ctx, rawInvitation, token); err != nil { t.Fatal(err) }

	badAcceptance := InvitationAcceptance{
		Version: ManifestVersion, InviteID: invitation.InviteID, CommunityID: invitation.CommunityID,
		MemberPublicID: member.id, AcceptedAt: now.UnixMilli(),
	}
	badPayload, _ := CanonicalInvitationAcceptance(badAcceptance, invitation.TokenHash)
	badAcceptance.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(stranger.private, badPayload))
	if _, err := store.AcceptInvitation(ctx, rawInvitation, token, badAcceptance); err == nil {
		t.Fatal("acceptance signed by a different identity was accepted")
	}
	if store.CanReadPrivate(ctx, manifest.CommunityID, member.id) {
		t.Fatal("failed acceptance granted private access")
	}

	acceptance := badAcceptance
	acceptance.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(member.private, badPayload))
	if _, err := store.AcceptInvitation(ctx, rawInvitation, base64.RawURLEncoding.EncodeToString(make([]byte, 32)), acceptance); err == nil {
		t.Fatal("wrong invitation bearer token was accepted")
	}
	if _, err := store.AcceptInvitation(ctx, rawInvitation, token, acceptance); err != nil { t.Fatal(err) }
	if !store.CanReadPrivate(ctx, manifest.CommunityID, member.id) {
		t.Fatal("valid member acceptance did not grant private read access")
	}
	if store.CanReadPrivate(ctx, manifest.CommunityID, stranger.id) {
		t.Fatal("uninvited identity gained private read access")
	}
	if _, err := store.GetManifestForMember(ctx, manifest.CommunityID, stranger.id); err == nil {
		t.Fatal("private manifest leaked to a non-member")
	}
}

func TestPrivateAccessProofBindsMethodPathIdentityAndTime(t *testing.T) {
	member := membershipIdentity(t)
	now := time.Unix(1_800_000_000, 0)
	method, path, timestamp := "GET", "/v1/communities/id/private", now.UnixMilli()
	payload, err := canonicalAccess(method, path, member.id, timestamp)
	if err != nil { t.Fatal(err) }
	signature := base64.RawURLEncoding.EncodeToString(ed25519.Sign(member.private, payload))
	if err := VerifyAccessProof(method, path, member.id, timestamp, signature, now); err != nil {
		t.Fatal(err)
	}
	if err := VerifyAccessProof("POST", path, member.id, timestamp, signature, now); err == nil {
		t.Fatal("proof was replayed against a different HTTP method")
	}
	if err := VerifyAccessProof(method, path+"/events", member.id, timestamp, signature, now); err == nil {
		t.Fatal("proof was replayed against a different path")
	}
	if err := VerifyAccessProof(method, path, member.id, timestamp, signature, now.Add(6*time.Minute)); err == nil {
		t.Fatal("expired access proof was accepted")
	}
}

func TestSignedBanBlocksPrivateMembershipAndUnbanAllowsFreshAcceptance(t *testing.T) {
    ctx := context.Background()
    now := time.Unix(1_800_000_000, 0)
    store := testStore(now)
    owner := membershipIdentity(t)
    member := membershipIdentity(t)
    manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "private", nil, now)
    if _, err := store.PublishManifest(ctx, mustJSON(t, manifest)); err != nil { t.Fatal(err) }

    tokenBytes := make([]byte, 32)
    if _, err := rand.Read(tokenBytes); err != nil { t.Fatal(err) }
    token := base64.RawURLEncoding.EncodeToString(tokenBytes)
    digest, _ := invitationTokenHash(token)
    invitation := Invitation{
        Version: ManifestVersion,
        InviteID: "b1b2c3d4-e5f6-4789-8abc-0123456789ab",
        CommunityID: manifest.CommunityID,
        OwnerPublicID: owner.id,
        MemberPublicID: member.id,
        PolicyVersion: manifest.PolicyVersion,
        TokenHash: digest,
        CreatedAt: now.UnixMilli(),
        ExpiresAt: now.Add(24 * time.Hour).UnixMilli(),
    }
    payload, err := CanonicalInvitation(invitation)
    if err != nil { t.Fatal(err) }
    invitation.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(owner.private, payload))
    raw := mustJSON(t, invitation)
    if _, err := store.CreateInvitation(ctx, raw, token); err != nil { t.Fatal(err) }

    ban := signedEvent(t, owner, manifest, 1, "ban", member.id, now)
    if _, err := store.AppendModerationEvent(ctx, mustJSON(t, ban)); err != nil { t.Fatal(err) }
    if banned, err := store.IsBanned(ctx, manifest.CommunityID, member.id); err != nil || !banned {
        t.Fatalf("signed ban was not persisted: banned=%v err=%v", banned, err)
    }
    if store.CanReadPrivate(ctx, manifest.CommunityID, member.id) {
        t.Fatal("banned member retained private community access")
    }

    acceptance := InvitationAcceptance{
        Version: ManifestVersion,
        InviteID: invitation.InviteID,
        CommunityID: invitation.CommunityID,
        MemberPublicID: member.id,
        AcceptedAt: now.UnixMilli(),
    }
    acceptancePayload, _ := CanonicalInvitationAcceptance(acceptance, invitation.TokenHash)
    acceptance.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(member.private, acceptancePayload))
    if _, err := store.AcceptInvitation(ctx, raw, token, acceptance); err == nil {
        t.Fatal("banned identity redeemed a previously issued invitation")
    }

    unban := signedEvent(t, owner, manifest, 2, "unban", member.id, now)
    if _, err := store.AppendModerationEvent(ctx, mustJSON(t, unban)); err != nil { t.Fatal(err) }
    if banned, err := store.IsBanned(ctx, manifest.CommunityID, member.id); err != nil || banned {
        t.Fatalf("unban did not clear the restriction: banned=%v err=%v", banned, err)
    }
    if _, err := store.AcceptInvitation(ctx, raw, token, acceptance); err != nil {
        t.Fatalf("identity could not accept its still-valid invitation after unban: %v", err)
    }
    if !store.CanReadPrivate(ctx, manifest.CommunityID, member.id) {
        t.Fatal("member did not regain private access after unban and acceptance")
    }
}

func TestOwnerRemovalRevokesPrivateCommunityMembership(t *testing.T) {
	ctx := context.Background()
	now := time.Unix(1_800_000_000, 0)
	store := NewStoreWithClock(sync.MutexWrap(datastore.NewMapDatastore()), func() time.Time { return now })
	owner := membershipIdentity(t)
	member := membershipIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "private", nil, now)
	if _, err := store.PublishManifest(ctx, mustJSON(t, manifest)); err != nil { t.Fatal(err) }
	tokenBytes := make([]byte, 32)
	_, _ = rand.Read(tokenBytes)
	token := base64.RawURLEncoding.EncodeToString(tokenBytes)
	digest, _ := invitationTokenHash(token)
	invitation := Invitation{
		Version: ManifestVersion, InviteID: "b1b2c3d4-e5f6-4789-8abc-0123456789ab",
		CommunityID: manifest.CommunityID, OwnerPublicID: owner.id, MemberPublicID: member.id,
		PolicyVersion: manifest.PolicyVersion, TokenHash: digest,
		CreatedAt: now.UnixMilli(), ExpiresAt: now.Add(24*time.Hour).UnixMilli(),
	}
	payload, _ := CanonicalInvitation(invitation)
	invitation.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(owner.private, payload))
	raw := mustJSON(t, invitation)
	if _, err := store.CreateInvitation(ctx, raw, token); err != nil { t.Fatal(err) }
	acceptance := InvitationAcceptance{
		Version: ManifestVersion, InviteID: invitation.InviteID, CommunityID: invitation.CommunityID,
		MemberPublicID: member.id, AcceptedAt: now.UnixMilli(),
	}
	ap, _ := CanonicalInvitationAcceptance(acceptance, invitation.TokenHash)
	acceptance.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(member.private, ap))
	if _, err := store.AcceptInvitation(ctx, raw, token, acceptance); err != nil { t.Fatal(err) }
	if !store.CanReadPrivate(ctx, manifest.CommunityID, member.id) { t.Fatal("membership not granted") }

	event := signedEvent(t, testIdentity{id: owner.id, private: owner.private}, manifest, 1, "remove_member", member.id, now)
	if _, err := store.AppendModerationEvent(ctx, mustJSON(t, event)); err != nil { t.Fatal(err) }
	if store.CanReadPrivate(ctx, manifest.CommunityID, member.id) {
		t.Fatal("removed member retained private community read access")
	}
}
