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
	membership := Membership{
		CommunityID: manifest.CommunityID, MemberPublicID: reporter.id,
		InviteID: "d1b2c3d4-e5f6-4789-8abc-0123456789ab", JoinedAt: now.UnixMilli(), PolicyVersion: manifest.PolicyVersion,
	}
	membershipBytes, err := json.Marshal(membership)
	if err != nil { t.Fatal(err) }
	if err := store.ds.Put(context.Background(), membershipKey(manifest.CommunityID, reporter.id), membershipBytes); err != nil {
		t.Fatal(err)
	}

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


func signedPost(t *testing.T, author testIdentity, communityID string, sequence uint64, body string, now time.Time) Post {
	t.Helper()
	post := Post{
		Version: ManifestVersion,
		PostID: "d1b2c3d4-e5f6-4789-8abc-0123456789ab",
		CommunityID: communityID,
		AuthorPublicID: author.id,
		Sequence: sequence,
		Body: body,
		CreatedAt: now.UnixMilli(),
	}
	payload, err := CanonicalPost(post)
	if err != nil { t.Fatal(err) }
	post.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(author.private, payload))
	return post
}

func signedReview(t *testing.T, reviewer testIdentity, communityID, reportID, status, note string, sequence uint64, now time.Time) ReportReview {
	t.Helper()
	review := ReportReview{
		Version: ManifestVersion,
		ReviewID: "e1b2c3d4-e5f6-4789-8abc-0123456789ab",
		CommunityID: communityID,
		ReportID: reportID,
		ReviewerPublicID: reviewer.id,
		Sequence: sequence,
		Status: status,
		Note: note,
		CreatedAt: now.UnixMilli(),
	}
	payload, err := CanonicalReportReview(review)
	if err != nil { t.Fatal(err) }
	review.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(reviewer.private, payload))
	return review
}

func TestSignedCommunityPostsAreSequencedIdempotentAndOrdered(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner, author := makeIdentity(t), makeIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", nil, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	first := signedPost(t, author, manifest.CommunityID, 1, "first post", now)
	raw := mustJSON(t, first)
	accepted, err := store.PublishPost(context.Background(), raw)
	if err != nil || accepted.PostID != first.PostID { t.Fatalf("first post rejected: %v", err) }
	if _, err := store.PublishPost(context.Background(), raw); err != nil {
		t.Fatalf("exact retry should be idempotent: %v", err)
	}
	replay := signedPost(t, author, manifest.CommunityID, 1, "different body", now)
	replay.PostID = "d2b2c3d4-e5f6-4789-8abc-0123456789ab"
	payload, _ := CanonicalPost(replay)
	replay.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(author.private, payload))
	if _, err := store.PublishPost(context.Background(), mustJSON(t, replay)); err == nil {
		t.Fatal("reused post sequence was accepted")
	}

	second := signedPost(t, author, manifest.CommunityID, 2, "second post", now.Add(time.Second))
	second.PostID = "d3b2c3d4-e5f6-4789-8abc-0123456789ab"
	payload, _ = CanonicalPost(second)
	second.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(author.private, payload))
	if _, err := store.PublishPost(context.Background(), mustJSON(t, second)); err != nil { t.Fatal(err) }

	feed, err := store.Posts(context.Background(), manifest.CommunityID, "", 20)
	if err != nil { t.Fatal(err) }
	if len(feed) != 2 || feed[0].Body != "second post" || feed[1].Body != "first post" {
		t.Fatalf("feed order is wrong: %#v", feed)
	}
	last, err := store.LastPostSequence(context.Background(), manifest.CommunityID, author.id)
	if err != nil || last != 2 { t.Fatalf("unexpected post sequence %d (%v)", last, err) }
}

func TestCommunityMuteSlowModeBanAndAllowPostAreEnforcedAtPublish(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := NewStoreWithClock(syncds.MutexWrap(datastore.NewMapDatastore()), func() time.Time { return now })
	owner, author := makeIdentity(t), makeIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", nil, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	publish := func(sequence uint64, id string) error {
		p := signedPost(t, author, manifest.CommunityID, sequence, "hello", now)
		p.PostID = id
		payload, err := CanonicalPost(p)
		if err != nil { return err }
		p.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(author.private, payload))
		_, err = store.PublishPost(context.Background(), mustJSON(t, p))
		return err
	}
	moderate := func(sequence uint64, action string) {
		t.Helper()
		event := signedEvent(t, owner, manifest, sequence, action, author.id, now)
		if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, event)); err != nil { t.Fatalf("%s failed: %v", action, err) }
	}

	if err := publish(1, "d1b2c3d4-e5f6-4789-8abc-0123456789ab"); err != nil { t.Fatal(err) }
	moderate(1, "mute")
	if err := publish(2, "d2b2c3d4-e5f6-4789-8abc-0123456789ab"); err == nil { t.Fatal("muted identity published") }
	moderate(2, "allow_post")
	if err := publish(2, "d2b2c3d4-e5f6-4789-8abc-0123456789ab"); err != nil { t.Fatalf("allow_post did not restore publishing: %v", err) }
	moderate(3, "ban")
	if err := publish(3, "d3b2c3d4-e5f6-4789-8abc-0123456789ab"); err == nil { t.Fatal("banned identity published") }
	moderate(4, "unban")
	moderate(5, "slow_mode")
	if err := publish(3, "d3b2c3d4-e5f6-4789-8abc-0123456789ab"); err == nil { t.Fatal("slow mode did not enforce the cooldown") }
	now = now.Add(slowModeInterval + time.Second)
	if err := publish(3, "d3b2c3d4-e5f6-4789-8abc-0123456789ab"); err != nil { t.Fatalf("slow mode did not release after cooldown: %v", err) }
}

func TestReportReviewLifecycleRequiresAuthorityAndKeepsAudit(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner, moderator, reporter, stranger := makeIdentity(t), makeIdentity(t), makeIdentity(t), makeIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", []string{moderator.id}, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	report := Report{
		Version: ManifestVersion, ReportID: "c1b2c3d4-e5f6-4789-8abc-0123456789ab",
		CommunityID: manifest.CommunityID, ReporterPublicID: reporter.id,
		Category: "spam", Summary: "Repeated spam posts", CreatedAt: now.UnixMilli(),
	}
	payload, err := canonicalReport(report)
	if err != nil { t.Fatal(err) }
	report.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(reporter.private, payload))
	if _, err := store.SubmitReport(context.Background(), mustJSON(t, report)); err != nil { t.Fatal(err) }

	unauthorized := signedReview(t, stranger, manifest.CommunityID, report.ReportID, "in_review", "", 1, now)
	if _, err := store.ReviewReport(context.Background(), mustJSON(t, unauthorized)); err == nil {
		t.Fatal("non-moderator reviewed a report")
	}
	assigned := signedReview(t, moderator, manifest.CommunityID, report.ReportID, "in_review", "Assigned for review", 1, now)
	if _, err := store.ReviewReport(context.Background(), mustJSON(t, assigned)); err != nil { t.Fatal(err) }
	resolved := signedReview(t, owner, manifest.CommunityID, report.ReportID, "resolved", "Removed the abusive content", 1, now)
	resolved.ReviewID = "e2b2c3d4-e5f6-4789-8abc-0123456789ab"
	payload, _ = CanonicalReportReview(resolved)
	resolved.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(owner.private, payload))
	if _, err := store.ReviewReport(context.Background(), mustJSON(t, resolved)); err != nil { t.Fatal(err) }

	late := signedReview(t, moderator, manifest.CommunityID, report.ReportID, "in_review", "", 2, now)
	late.ReviewID = "e3b2c3d4-e5f6-4789-8abc-0123456789ab"
	payload, _ = CanonicalReportReview(late)
	late.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(moderator.private, payload))
	if _, err := store.ReviewReport(context.Background(), mustJSON(t, late)); err == nil {
		t.Fatal("resolved report was reopened")
	}

	views, err := store.ReportViews(context.Background(), manifest.CommunityID, moderator.id, 20)
	if err != nil || len(views) != 1 || views[0].Status != "resolved" {
		t.Fatalf("report status was not persisted: %#v (%v)", views, err)
	}
	audit, err := store.ReportAudit(context.Background(), manifest.CommunityID, report.ReportID, moderator.id, 20)
	if err != nil || len(audit) != 2 || audit[0].Review.Status != "in_review" || audit[1].Review.Status != "resolved" {
		t.Fatalf("report audit is incomplete: %#v (%v)", audit, err)
	}
}
