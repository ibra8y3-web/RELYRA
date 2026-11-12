package community

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	datastore "github.com/ipfs/go-datastore"
	syncds "github.com/ipfs/go-datastore/sync"
	identity "github.com/ibra8y3-web/RELYRA/services/discovery/internal/protocol"
)

type testIdentity struct {
	id string
	private ed25519.PrivateKey
}

func makeIdentity(t *testing.T) testIdentity {
	t.Helper()
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil { t.Fatal(err) }
	return testIdentity{id: identity.PublicIDForKey(public), private: private}
}

func signedManifest(t *testing.T, owner testIdentity, communityID, visibility string, moderators []string, now time.Time) Manifest {
	t.Helper()
	m := Manifest{
		Version: ManifestVersion, CommunityID: communityID, OwnerPublicID: owner.id,
		Name: "Community", Description: "Publicly visible summary only",
		Visibility: visibility, ModeratorPublicIDs: append([]string(nil), moderators...),
		PolicyVersion: 1, CreatedAt: now.UnixMilli(), UpdatedAt: now.UnixMilli(),
	}
	payload, err := canonicalManifest(m)
	if err != nil { t.Fatal(err) }
	m.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(owner.private, payload))
	return m
}

func signedEvent(t *testing.T, actor testIdentity, manifest Manifest, seq uint64, action, subject string, now time.Time) ModerationEvent {
	t.Helper()
	e := ModerationEvent{
		Version: ManifestVersion, EventID: "a1b2c3d4-e5f6-4789-8abc-0123456789ab",
		CommunityID: manifest.CommunityID, ActorPublicID: actor.id,
		Sequence: seq, PolicyVersion: manifest.PolicyVersion, Action: action,
		SubjectPublicID: subject, ReasonCode: "community_rules",
		CreatedAt: now.UnixMilli(), ExpiresAt: now.Add(24*time.Hour).UnixMilli(),
	}
	payload, err := canonicalEvent(e)
	if err != nil { t.Fatal(err) }
	e.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(actor.private, payload))
	return e
}

func testStore(now time.Time) *Store {
	return NewStoreWithClock(syncds.MutexWrap(datastore.NewMapDatastore()), func() time.Time { return now })
}

func TestPublicAndPrivateManifestIndexSeparation(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner := makeIdentity(t)
	public := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", nil, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, public)); err != nil { t.Fatal(err) }

	private := signedManifest(t, owner, "b1b2c3d4-e5f6-4789-8abc-0123456789ab", "private", nil, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, private)); err != nil { t.Fatal(err) }

	found, err := store.Discover(context.Background(), 20)
	if err != nil { t.Fatal(err) }
	if len(found) != 1 || found[0].CommunityID != public.CommunityID {
		t.Fatalf("private community leaked into public discovery: %#v", found)
	}
	if _, err := store.GetManifest(context.Background(), private.CommunityID, false); !errors.Is(err, datastore.ErrNotFound) {
		t.Fatalf("private community must not be publicly fetchable: %v", err)
	}
	if _, err := store.GetManifest(context.Background(), private.CommunityID, true); err != nil {
		t.Fatalf("owner-authorized private retrieval failed: %v", err)
	}
}

func TestManifestRejectsTamperingAndOwnerChange(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner := makeIdentity(t)
	other := makeIdentity(t)
	m := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", nil, now)
	m.Name = "tampered after signature"
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, m)); err == nil {
		t.Fatal("tampered manifest was accepted")
	}

	valid := signedManifest(t, owner, m.CommunityID, "public", nil, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, valid)); err != nil { t.Fatal(err) }
	update := valid
	update.PolicyVersion++
	update.UpdatedAt++
	update.OwnerPublicID = other.id
	payload, err := canonicalManifest(update)
	if err != nil { t.Fatal(err) }
	update.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(other.private, payload))
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, update)); err == nil {
		t.Fatal("community ownership transfer was accepted without a separate protocol")
	}
}

func TestModerationAuthorizationSequenceAndReplayProtection(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner := makeIdentity(t)
	moderator := makeIdentity(t)
	stranger := makeIdentity(t)
	subject := makeIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", []string{moderator.id}, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	allowed := signedEvent(t, moderator, manifest, 1, "mute", subject.id, now)
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, allowed)); err != nil { t.Fatal(err) }
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, allowed)); err == nil {
		t.Fatal("replayed moderation event was accepted")
	}

	gap := signedEvent(t, moderator, manifest, 3, "ban", subject.id, now)
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, gap)); err == nil {
		t.Fatal("sequence gap was accepted")
	}

	unauthorized := signedEvent(t, stranger, manifest, 1, "ban", subject.id, now)
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, unauthorized)); err == nil {
		t.Fatal("non-moderator event was accepted")
	}

	moderatorRemove := signedEvent(t, moderator, manifest, 2, "remove_member", subject.id, now)
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, moderatorRemove)); err == nil {
		t.Fatal("moderator was allowed to remove a member")
	}

	ownerRemove := signedEvent(t, owner, manifest, 1, "remove_member", subject.id, now)
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, ownerRemove)); err != nil {
		t.Fatalf("owner remove event rejected: %v", err)
	}
	events, err := store.Events(context.Background(), manifest.CommunityID, 20)
	if err != nil { t.Fatal(err) }
	if len(events) != 2 { t.Fatalf("expected 2 accepted events, got %d", len(events)) }
}

func TestOptInReportContainsOnlyDeclaredRedactedFields(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner := makeIdentity(t)
	reporter := makeIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "private", nil, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	report := Report{
		Version: ManifestVersion, ReportID: "c1b2c3d4-e5f6-4789-8abc-0123456789ab",
		CommunityID: manifest.CommunityID, ReporterPublicID: reporter.id,
		Category: "spam", Summary: "Unsolicited advertisements", CreatedAt: now.UnixMilli(),
	}
	payload, err := canonicalReport(report)
	if err != nil { t.Fatal(err) }
	report.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(reporter.private, payload))
	raw := mustJSON(t, report)
	if _, err := store.SubmitReport(context.Background(), raw); err != nil { t.Fatal(err) }

	if _, err := store.Reports(context.Background(), manifest.CommunityID, reporter.id, 20); err == nil {
		t.Fatal("ordinary community member was allowed to read private reports")
	}
	reports, err := store.Reports(context.Background(), manifest.CommunityID, owner.id, 20)
	if err != nil { t.Fatal(err) }
	if len(reports) != 1 || reports[0].ReportID != report.ReportID {
		t.Fatalf("moderator report inbox returned unexpected data: %#v", reports)
	}

	// An unexpected private-message-content field is rejected, not silently retained.
	withPrivateContent := strings.TrimSuffix(string(raw), "}") + `, "messageContent":"secret private conversation"}`
	if _, err := store.SubmitReport(context.Background(), []byte(withPrivateContent)); err == nil {
		t.Fatal("report accepted undeclared private message content")
	}
}

func mustJSON(t *testing.T, value any) []byte {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil { t.Fatal(err) }
	return raw
}
