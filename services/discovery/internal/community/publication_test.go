package community

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"testing"
	"time"
)

func signedPostForTest(t *testing.T, author testIdentity, communityID string, sequence uint64, body string, now time.Time) Post {
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

func signedReportForTest(t *testing.T, reporter testIdentity, communityID, reportID string, now time.Time) Report {
	t.Helper()
	report := Report{
		Version: ManifestVersion, ReportID: reportID, CommunityID: communityID,
		ReporterPublicID: reporter.id, Category: "spam", Summary: "Repeated unsolicited posts",
		CreatedAt: now.UnixMilli(),
	}
	payload, err := canonicalReport(report)
	if err != nil { t.Fatal(err) }
	report.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(reporter.private, payload))
	return report
}

func signedReviewForTest(t *testing.T, reviewer testIdentity, communityID, reportID, reviewID, status string, sequence uint64, note string, now time.Time) ReportReview {
	t.Helper()
	review := ReportReview{
		Version: ManifestVersion, ReviewID: reviewID, CommunityID: communityID,
		ReportID: reportID, ReviewerPublicID: reviewer.id, Sequence: sequence,
		Status: status, Note: note, CreatedAt: now.UnixMilli(),
	}
	payload, err := CanonicalReportReview(review)
	if err != nil { t.Fatal(err) }
	review.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(reviewer.private, payload))
	return review
}

func TestSignedCommunityPostsAreSequencedIdempotentAndFed(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner := makeIdentity(t)
	author := makeIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", nil, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	post := signedPostForTest(t, author, manifest.CommunityID, 1, "A real signed community post", now)
	raw := mustJSON(t, post)
	if accepted, err := store.PublishPost(context.Background(), raw); err != nil || accepted.Signature != post.Signature {
		t.Fatalf("valid signed post rejected: %#v, %v", accepted, err)
	}
	if _, err := store.PublishPost(context.Background(), raw); err != nil {
		t.Fatalf("idempotent retry was rejected: %v", err)
	}
	sequence, err := store.LastPostSequence(context.Background(), manifest.CommunityID, author.id)
	if err != nil || sequence != 1 { t.Fatalf("unexpected post sequence %d: %v", sequence, err) }

	feed, err := store.Posts(context.Background(), manifest.CommunityID, author.id, 20)
	if err != nil || len(feed) != 1 || feed[0].Body != post.Body {
		t.Fatalf("feed did not return the verified post: %#v, %v", feed, err)
	}
	tampered := post
	tampered.Body = "altered after signing"
	if _, err := store.PublishPost(context.Background(), mustJSON(t, tampered)); err == nil {
		t.Fatal("tampered signed post was accepted")
	}
	gap := signedPostForTest(t, author, manifest.CommunityID, 3, "sequence gap", now)
	gap.PostID = "e1b2c3d4-e5f6-4789-8abc-0123456789ab"
	gap.Signature = ""
	payload, _ := CanonicalPost(gap)
	gap.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(author.private, payload))
	if _, err := store.PublishPost(context.Background(), mustJSON(t, gap)); err == nil {
		t.Fatal("post sequence gap was accepted")
	}
}

func TestPostingPolicyIsEnforcedAtPublication(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner := makeIdentity(t)
	moderator := makeIdentity(t)
	member := makeIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", []string{moderator.id}, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	mute := signedEvent(t, moderator, manifest, 1, "mute", member.id, now)
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, mute)); err != nil { t.Fatal(err) }
	first := signedPostForTest(t, member, manifest.CommunityID, 1, "not allowed while muted", now)
	if _, err := store.PublishPost(context.Background(), mustJSON(t, first)); err == nil {
		t.Fatal("muted identity published")
	}

	allow := signedEvent(t, owner, manifest, 1, "allow_post", member.id, now)
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, allow)); err != nil { t.Fatal(err) }
	first.Body = "allowed after owner override"
	first.Signature = ""
	payload, _ := CanonicalPost(first)
	first.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(member.private, payload))
	if _, err := store.PublishPost(context.Background(), mustJSON(t, first)); err != nil {
		t.Fatalf("owner allow_post override was not applied: %v", err)
	}

	slow := signedEvent(t, moderator, manifest, 2, "slow_mode", member.id, now)
	if _, err := store.AppendModerationEvent(context.Background(), mustJSON(t, slow)); err != nil { t.Fatal(err) }
	second := signedPostForTest(t, member, manifest.CommunityID, 2, "too soon", now.Add(5*time.Second))
	second.PostID = "e1b2c3d4-e5f6-4789-8abc-0123456789ac"
	second.Signature = ""
	payload, _ = CanonicalPost(second)
	second.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(member.private, payload))
	if _, err := store.PublishPost(context.Background(), mustJSON(t, second)); err == nil {
		t.Fatal("slow mode did not block an early post")
	}

	now = now.Add(31 * time.Second)
	second.CreatedAt = now.UnixMilli()
	second.Signature = ""
	payload, _ = CanonicalPost(second)
	second.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(member.private, payload))
	if _, err := store.PublishPost(context.Background(), mustJSON(t, second)); err != nil {
		t.Fatalf("slow mode did not release after its interval: %v", err)
	}
}

func TestReportReviewHasRoleGatesStateAndAppendOnlyAudit(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	store := testStore(now)
	owner := makeIdentity(t)
	moderator := makeIdentity(t)
	reporter := makeIdentity(t)
	manifest := signedManifest(t, owner, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", "public", []string{moderator.id}, now)
	if _, err := store.PublishManifest(context.Background(), mustJSON(t, manifest)); err != nil { t.Fatal(err) }

	report := signedReportForTest(t, reporter, manifest.CommunityID, "c1b2c3d4-e5f6-4789-8abc-0123456789ab", now)
	if _, err := store.SubmitReport(context.Background(), mustJSON(t, report)); err != nil { t.Fatal(err) }

	assign := signedReviewForTest(t, moderator, manifest.CommunityID, report.ReportID,
		"d1b2c3d4-e5f6-4789-8abc-0123456789ab", "in_review", 1, "Investigating", now)
	if _, err := store.ReviewReport(context.Background(), mustJSON(t, assign)); err != nil { t.Fatal(err) }

	moderatorResolve := signedReviewForTest(t, moderator, manifest.CommunityID, report.ReportID,
		"e1b2c3d4-e5f6-4789-8abc-0123456789ab", "resolved", 2, "Resolved by moderator", now)
	if _, err := store.ReviewReport(context.Background(), mustJSON(t, moderatorResolve)); err == nil {
		t.Fatal("moderator resolved a report without owner authority")
	}

	resolve := signedReviewForTest(t, owner, manifest.CommunityID, report.ReportID,
		"f1b2c3d4-e5f6-4789-8abc-0123456789ab", "resolved", 1, "Reviewed and action completed", now)
	if _, err := store.ReviewReport(context.Background(), mustJSON(t, resolve)); err != nil {
		t.Fatalf("owner resolution rejected: %v", err)
	}
	reopen := signedReviewForTest(t, owner, manifest.CommunityID, report.ReportID,
		"a2b2c3d4-e5f6-4789-8abc-0123456789ab", "in_review", 2, "", now)
	if _, err := store.ReviewReport(context.Background(), mustJSON(t, reopen)); err == nil {
		t.Fatal("closed report was reopened")
	}

	views, err := store.ReportViews(context.Background(), manifest.CommunityID, moderator.id, 20)
	if err != nil || len(views) != 1 || views[0].Status != "resolved" ||
		views[0].ReviewerPublicID != owner.id || views[0].DecisionNote != resolve.Note {
		t.Fatalf("report state was not persisted: %#v, %v", views, err)
	}
	audit, err := store.ReportAudit(context.Background(), manifest.CommunityID, report.ReportID, moderator.id, 20)
	if err != nil || len(audit) != 2 || audit[0].Order != 1 || audit[1].Order != 2 {
		t.Fatalf("expected ordered immutable review audit, got %#v, %v", audit, err)
	}
}

func TestPostVerificationRejectsExpiredAndMalformedPosts(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	author := makeIdentity(t)
	post := signedPostForTest(t, author, "a1b2c3d4-e5f6-4789-8abc-0123456789ab", 1, "hello", now)
	if err := VerifyPost(post, now.Add(25*time.Hour)); err == nil {
		t.Fatal("expired post was verified")
	}
	post.Body = strings.Repeat("x", MaxPostBody+1)
	if _, err := CanonicalPost(post); err == nil {
		t.Fatal("oversized post body was accepted")
	}
}
