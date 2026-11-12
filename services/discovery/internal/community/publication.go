package community

import (
	"encoding/base64"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	datastore "github.com/ipfs/go-datastore"
	"github.com/ipfs/go-datastore/query"
	identity "github.com/ibra8y3-web/RELYRA/services/discovery/internal/protocol"
)

const (
	MaxPostBytes = 12 * 1024
	MaxPostBody = 4000
	MaxFeedPage = 100
	postPrefix = "/relyra/community/post/"
	postSequencePrefix = "/relyra/community/post-sequence/"
	postPolicyPrefix = "/relyra/community/post-policy/"
	reportReviewPrefix = "/relyra/community/report-review/"
	reportReviewStatePrefix = "/relyra/community/report-review-state/"
	reportReviewOrderPrefix = "/relyra/community/report-review-order/"
	slowModeInterval = 30 * time.Second
)

type Post struct {
	Version int `json:"version"`
	PostID string `json:"postId"`
	CommunityID string `json:"communityId"`
	AuthorPublicID string `json:"authorPublicId"`
	Sequence uint64 `json:"sequence"`
	Body string `json:"body"`
	CreatedAt int64 `json:"createdAt"`
	Signature string `json:"signature"`
}

type postSigningPayload struct {
	Domain string `json:"domain"`
	Version int `json:"version"`
	PostID string `json:"postId"`
	CommunityID string `json:"communityId"`
	AuthorPublicID string `json:"authorPublicId"`
	Sequence uint64 `json:"sequence"`
	Body string `json:"body"`
	CreatedAt int64 `json:"createdAt"`
}

func CanonicalPost(p Post) ([]byte, error) {
	if p.Version != ManifestVersion || !validCommunityID(p.PostID) || !validCommunityID(p.CommunityID) {
		return nil, errors.New("invalid post ID or version")
	}
	if _, err := identity.DecodePublicID(p.AuthorPublicID); err != nil {
		return nil, errors.New("invalid post author identity")
	}
	if p.Sequence == 0 || p.CreatedAt <= 0 || strings.TrimSpace(p.Body) == "" ||
		len([]byte(p.Body)) > MaxPostBody || strings.ContainsRune(p.Body, '\x00') {
		return nil, errors.New("invalid post sequence, body or timestamp")
	}
	return []byte(strings.Join([]string{
		"RELYRA-COMMUNITY-POST-V1", strings.ToLower(p.PostID), strings.ToLower(p.CommunityID),
		strings.ToLower(p.AuthorPublicID), strconv.FormatUint(p.Sequence, 10),
		base64.RawURLEncoding.EncodeToString([]byte(p.Body)), strconv.FormatInt(p.CreatedAt, 10),
	}, "\n")), nil
}

func VerifyPost(p Post, now time.Time) error {
	payload, err := CanonicalPost(p)
	if err != nil { return err }
	created := time.UnixMilli(p.CreatedAt)
	if created.After(now.Add(5*time.Minute)) || now.Sub(created) > 24*time.Hour {
		return errors.New("post timestamp is outside the accepted window")
	}
	publicKey, err := identity.DecodePublicID(p.AuthorPublicID)
	if err != nil { return err }
	signature, err := base64.RawURLEncoding.DecodeString(p.Signature)
	if err != nil || len(signature) != 64 || !ed25519.Verify(publicKey, payload, signature) {
		return errors.New("invalid post author signature")
	}
	return nil
}

type postingPolicy struct {
	Banned bool `json:"banned"`
	BanUntil int64 `json:"banUntil"`
	Muted bool `json:"muted"`
	MuteUntil int64 `json:"muteUntil"`
	SlowMode bool `json:"slowMode"`
	SlowModeUntil int64 `json:"slowModeUntil"`
	AllowPost bool `json:"allowPost"`
	LastPostAt int64 `json:"lastPostAt"`
}

func postSequenceKey(communityID, authorID string) datastore.Key {
	return datastore.NewKey(postSequencePrefix + strings.ToLower(communityID) + "/" + strings.ToLower(authorID))
}
func postPolicyKey(communityID, authorID string) datastore.Key {
	return datastore.NewKey(postPolicyPrefix + strings.ToLower(communityID) + "/" + strings.ToLower(authorID))
}
func postKey(communityID, postID string) datastore.Key {
	return datastore.NewKey(postPrefix + strings.ToLower(communityID) + "/" + strings.ToLower(postID))
}

func readUint(ctx context.Context, ds datastore.Datastore, key datastore.Key) (uint64, error) {
	raw, err := ds.Get(ctx, key)
	if errors.Is(err, datastore.ErrNotFound) { return 0, nil }
	if err != nil { return 0, err }
	value, err := strconv.ParseUint(string(raw), 10, 64)
	if err != nil { return 0, errors.New("stored sequence is corrupt") }
	return value, nil
}

func (s *Store) LastPostSequence(ctx context.Context, communityID, authorID string) (uint64, error) {
	if !validCommunityID(communityID) { return 0, errors.New("invalid community ID") }
	if _, err := identity.DecodePublicID(authorID); err != nil { return 0, errors.New("invalid post author identity") }
	return readUint(ctx, s.ds, postSequenceKey(communityID, authorID))
}

func (s *Store) updatePostingPolicy(ctx context.Context, e ModerationEvent) error {
	key := postPolicyKey(e.CommunityID, e.SubjectPublicID)
	var policy postingPolicy
	raw, err := s.ds.Get(ctx, key)
	if err == nil {
		if json.Unmarshal(raw, &policy) != nil { return errors.New("stored community posting policy is corrupt") }
	} else if !errors.Is(err, datastore.ErrNotFound) { return err }

	switch e.Action {
	case "ban":
		policy.Banned = true
		policy.BanUntil = e.ExpiresAt
		policy.AllowPost = false
	case "unban":
		policy.Banned = false
		policy.BanUntil = 0
	case "mute":
		policy.Muted = true
		policy.MuteUntil = e.ExpiresAt
		policy.AllowPost = false
	case "slow_mode":
		policy.SlowMode = true
		policy.SlowModeUntil = e.ExpiresAt
		policy.AllowPost = false
	case "allow_post":
		policy.Muted = false
		policy.MuteUntil = 0
		policy.SlowMode = false
		policy.SlowModeUntil = 0
		policy.AllowPost = true
	default:
		return nil
	}
	encoded, err := json.Marshal(policy)
	if err != nil { return err }
	return s.ds.Put(ctx, key, encoded)
}

// PublishPost accepts only an author-signed post from a current community member.
// The per-author sequence and post ID make delivery retries idempotent.
func (s *Store) PublishPost(ctx context.Context, raw []byte) (Post, error) {
	var p Post
	if len(raw) == 0 || len(raw) > MaxPostBytes { return p, errors.New("post is oversized") }
	if err := decodeStrict(raw, &p); err != nil { return p, errors.New("invalid post JSON") }
	if err := VerifyPost(p, s.clock()); err != nil { return p, err }

	s.mu.Lock()
	defer s.mu.Unlock()
	manifest, err := s.GetManifest(ctx, p.CommunityID, true)
	if err != nil { return p, err }
	if manifest.Visibility == "private" && !s.CanReadPrivate(ctx, p.CommunityID, p.AuthorPublicID) {
		return p, errors.New("only current private community members may publish")
	}
	now := s.clock().UnixMilli()
	if banned, err := s.IsBanned(ctx, p.CommunityID, p.AuthorPublicID); err != nil || banned {
		return p, errors.New("identity is not allowed to publish in this community")
	}

	policyKey := postPolicyKey(p.CommunityID, p.AuthorPublicID)
	var policy postingPolicy
	if rawPolicy, getErr := s.ds.Get(ctx, policyKey); getErr == nil {
		if json.Unmarshal(rawPolicy, &policy) != nil { return p, errors.New("stored community posting policy is corrupt") }
	} else if !errors.Is(getErr, datastore.ErrNotFound) { return p, getErr }
	if policy.Banned && (policy.BanUntil == 0 || policy.BanUntil > now) {
		return p, errors.New("identity is banned from this community")
	}
	if policy.Muted && (policy.MuteUntil == 0 || policy.MuteUntil > now) && !policy.AllowPost {
		return p, errors.New("posting is muted for this identity")
	}
	if policy.SlowMode && (policy.SlowModeUntil == 0 || policy.SlowModeUntil > now) && !policy.AllowPost &&
		policy.LastPostAt > 0 && now-policy.LastPostAt < slowModeInterval.Milliseconds() {
		return p, errors.New("slow mode is active; wait before publishing again")
	}

	key := postKey(p.CommunityID, p.PostID)
	if existingRaw, getErr := s.ds.Get(ctx, key); getErr == nil {
		var existing Post
		if json.Unmarshal(existingRaw, &existing) != nil { return p, errors.New("stored post is corrupt") }
		if existing.Signature != p.Signature { return p, errors.New("post ID is already used") }
		last, seqErr := readUint(ctx, s.ds, postSequenceKey(p.CommunityID, p.AuthorPublicID))
		if seqErr != nil { return p, seqErr }
		if p.Sequence == last+1 {
			if err := s.ds.Put(ctx, postSequenceKey(p.CommunityID, p.AuthorPublicID), []byte(strconv.FormatUint(p.Sequence, 10))); err != nil { return p, err }
		} else if p.Sequence > last {
			return p, errors.New("post sequence has a gap")
		}
		if p.CreatedAt > policy.LastPostAt {
			policy.LastPostAt = p.CreatedAt
			encoded, _ := json.Marshal(policy)
			if err := s.ds.Put(ctx, policyKey, encoded); err != nil { return p, err }
		}
		return existing, nil
	} else if !errors.Is(getErr, datastore.ErrNotFound) { return p, getErr }

	last, err := readUint(ctx, s.ds, postSequenceKey(p.CommunityID, p.AuthorPublicID))
	if err != nil { return p, err }
	if p.Sequence != last+1 { return p, errors.New("post sequence is stale, duplicated or has a gap") }

	encoded, err := json.Marshal(p)
	if err != nil { return p, err }
	if err := s.ds.Put(ctx, key, encoded); err != nil { return p, err }
	if err := s.ds.Put(ctx, postSequenceKey(p.CommunityID, p.AuthorPublicID), []byte(strconv.FormatUint(p.Sequence, 10))); err != nil {
		return p, err
	}
	policy.LastPostAt = maxInt64(policy.LastPostAt, p.CreatedAt)
	policyBytes, err := json.Marshal(policy)
	if err != nil { return p, err }
	if err := s.ds.Put(ctx, policyKey, policyBytes); err != nil { return p, err }
	return p, nil
}

func maxInt64(a, b int64) int64 { if a > b { return a }; return b }

func (s *Store) Posts(ctx context.Context, communityID, requesterID string, limit int) ([]Post, error) {
	if !validCommunityID(communityID) || limit < 1 || limit > MaxFeedPage {
		return nil, errors.New("invalid community feed query")
	}
	manifest, err := s.GetManifest(ctx, communityID, true)
	if err != nil { return nil, err }
	if manifest.Visibility == "private" && !s.CanReadPrivate(ctx, communityID, requesterID) {
		return nil, errors.New("private community feed access denied")
	}
	results, err := s.ds.Query(ctx, query.Query{Prefix: postPrefix + strings.ToLower(communityID) + "/", Limit: 1000})
	if err != nil { return nil, err }
	defer results.Close()
	out := make([]Post, 0, limit)
	for entry := range results.Next() {
		if entry.Error != nil { return nil, entry.Error }
		var p Post
		if json.Unmarshal(entry.Value, &p) != nil || !strings.EqualFold(p.CommunityID, communityID) ||
			VerifyPost(p, s.clock()) != nil { continue }
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt == out[j].CreatedAt {
			if out[i].Sequence == out[j].Sequence { return out[i].PostID > out[j].PostID }
			return out[i].Sequence > out[j].Sequence
		}
		return out[i].CreatedAt > out[j].CreatedAt
	})
	if len(out) > limit { out = out[:limit] }
	return out, nil
}

type ReportReview struct {
	Version int `json:"version"`
	ReviewID string `json:"reviewId"`
	CommunityID string `json:"communityId"`
	ReportID string `json:"reportId"`
	ReviewerPublicID string `json:"reviewerPublicId"`
	Sequence uint64 `json:"sequence"`
	Status string `json:"status"`
	Note string `json:"note"`
	CreatedAt int64 `json:"createdAt"`
	Signature string `json:"signature"`
}
type reportReviewSigningPayload struct {
	Domain string `json:"domain"`
	Version int `json:"version"`
	ReviewID string `json:"reviewId"`
	CommunityID string `json:"communityId"`
	ReportID string `json:"reportId"`
	ReviewerPublicID string `json:"reviewerPublicId"`
	Sequence uint64 `json:"sequence"`
	Status string `json:"status"`
	Note string `json:"note"`
	CreatedAt int64 `json:"createdAt"`
}

func CanonicalReportReview(r ReportReview) ([]byte, error) {
	if r.Version != ManifestVersion || !validCommunityID(r.ReviewID) ||
		!validCommunityID(r.CommunityID) || !validCommunityID(r.ReportID) {
		return nil, errors.New("invalid report review ID or version")
	}
	if _, err := identity.DecodePublicID(r.ReviewerPublicID); err != nil { return nil, errors.New("invalid reviewer identity") }
	if r.Sequence == 0 || r.CreatedAt <= 0 || len(r.Note) > 256 || strings.ContainsRune(r.Note, '\x00') {
		return nil, errors.New("invalid report review sequence, note or timestamp")
	}
	if r.Status != "in_review" && r.Status != "resolved" && r.Status != "dismissed" {
		return nil, errors.New("unsupported report review status")
	}
	return []byte(strings.Join([]string{
		"RELYRA-COMMUNITY-REPORT-REVIEW-V1", strings.ToLower(r.ReviewID),
		strings.ToLower(r.CommunityID), strings.ToLower(r.ReportID),
		strings.ToLower(r.ReviewerPublicID), strconv.FormatUint(r.Sequence, 10),
		r.Status, base64.RawURLEncoding.EncodeToString([]byte(r.Note)), strconv.FormatInt(r.CreatedAt, 10),
	}, "\n")), nil
}

func VerifyReportReview(r ReportReview, now time.Time) error {
	payload, err := CanonicalReportReview(r)
	if err != nil { return err }
	created := time.UnixMilli(r.CreatedAt)
	if created.After(now.Add(5*time.Minute)) || now.Sub(created) > 30*24*time.Hour {
		return errors.New("report review timestamp is outside the accepted window")
	}
	publicKey, err := identity.DecodePublicID(r.ReviewerPublicID)
	if err != nil { return err }
	signature, err := base64.RawURLEncoding.DecodeString(r.Signature)
	if err != nil || len(signature) != 64 || !identity.VerifyPublicKey(publicKey, payload, signature) {
		return errors.New("invalid report review signature")
	}
	if (r.Status == "resolved" || r.Status == "dismissed") && strings.TrimSpace(r.Note) == "" {
		return errors.New("a resolution decision requires an audit note")
	}
	return nil
}

type ReportReviewState struct {
	Status string `json:"status"`
	ReviewerPublicID string `json:"reviewerPublicId"`
	Note string `json:"note"`
	UpdatedAt int64 `json:"updatedAt"`
}
type ReportView struct {
	Report Report `json:"report"`
	Status string `json:"status"`
	ReviewerPublicID string `json:"reviewerPublicId,omitempty"`
	DecisionNote string `json:"decisionNote,omitempty"`
	UpdatedAt int64 `json:"updatedAt"`
}
type ReportAuditEntry struct {
	Order uint64 `json:"order"`
	Review ReportReview `json:"review"`
}
type storedReportAuditEntry struct {
	Order uint64 `json:"order"`
	Review ReportReview `json:"review"`
}

func reportStateKey(communityID, reportID string) datastore.Key {
	return datastore.NewKey(reportReviewStatePrefix + strings.ToLower(communityID) + "/" + strings.ToLower(reportID))
}
func reportAuditPrefix(communityID, reportID string) string {
	return reportReviewPrefix + strings.ToLower(communityID) + "/" + strings.ToLower(reportID) + "/"
}
func reportAuditOrderKey(communityID, reportID string) datastore.Key {
	return datastore.NewKey(reportReviewOrderPrefix + strings.ToLower(communityID) + "/" + strings.ToLower(reportID))
}

func (s *Store) isCommunityModerator(ctx context.Context, manifest Manifest, requesterID string) bool {
	if _, err := identity.DecodePublicID(requesterID); err != nil { return false }
	if strings.EqualFold(manifest.OwnerPublicID, requesterID) { return true }
	for _, moderator := range manifest.ModeratorPublicIDs {
		if strings.EqualFold(moderator, requesterID) { return true }
	}
	return false
}

func (s *Store) ReviewReport(ctx context.Context, raw []byte) (ReportReview, error) {
	var review ReportReview
	if len(raw) == 0 || len(raw) > MaxEventBytes { return review, errors.New("report review is oversized") }
	if err := decodeStrict(raw, &review); err != nil { return review, errors.New("invalid report review JSON") }
	if err := VerifyReportReview(review, s.clock()); err != nil { return review, err }

	s.mu.Lock()
	defer s.mu.Unlock()
	manifest, err := s.GetManifest(ctx, review.CommunityID, true)
	if err != nil { return review, err }
	if manifest.Visibility == "private" && !s.CanReadPrivate(ctx, review.CommunityID, review.ReviewerPublicID) {
		return review, errors.New("reviewer is not a current private community member")
	}
	if !s.isCommunityModerator(ctx, manifest, review.ReviewerPublicID) {
		return review, errors.New("only the owner or a moderator may review reports")
	}
	if review.Status != "in_review" && !strings.EqualFold(manifest.OwnerPublicID, review.ReviewerPublicID) {
		return review, errors.New("only the community owner may resolve or dismiss reports")
	}
	reportRaw, err := s.ds.Get(ctx, datastore.NewKey(reportPrefix + strings.ToLower(review.CommunityID) + "/" + strings.ToLower(review.ReportID)))
	if err != nil { return review, errors.New("report does not exist") }
	var report Report
	if json.Unmarshal(reportRaw, &report) != nil || VerifyReport(report, s.clock()) != nil {
		return review, errors.New("stored report is invalid")
	}

	seqKey := datastore.NewKey(sequenceStorageKey(review.CommunityID, review.ReviewerPublicID))
	last, err := readUint(ctx, s.ds, seqKey)
	if err != nil { return review, err }
	if review.Sequence != last+1 { return review, errors.New("report review sequence is stale, duplicated or has a gap") }

	var current ReportReviewState
	stateKey := reportStateKey(review.CommunityID, review.ReportID)
	if stateRaw, stateErr := s.ds.Get(ctx, stateKey); stateErr == nil {
		if json.Unmarshal(stateRaw, &current) != nil { return review, errors.New("stored report review state is corrupt") }
	} else if !errors.Is(stateErr, datastore.ErrNotFound) { return review, stateErr }
	if current.Status == "resolved" || current.Status == "dismissed" {
		return review, errors.New("a closed report cannot be changed")
	}
	if review.Status == "in_review" && current.Status == "in_review" &&
		!strings.EqualFold(current.ReviewerPublicID, review.ReviewerPublicID) &&
		!strings.EqualFold(manifest.OwnerPublicID, review.ReviewerPublicID) {
		return review, errors.New("report is already assigned to another moderator")
	}
	orderKey := reportAuditOrderKey(review.CommunityID, review.ReportID)
	order, err := readUint(ctx, s.ds, orderKey)
	if err != nil { return review, err }
	entry := storedReportAuditEntry{Order: order+1, Review: review}
	encodedEntry, err := json.Marshal(entry)
	if err != nil { return review, err }
	encodedState, err := json.Marshal(ReportReviewState{
		Status: review.Status, ReviewerPublicID: review.ReviewerPublicID,
		Note: review.Note, UpdatedAt: review.CreatedAt,
	})
	if err != nil { return review, err }
	auditKey := datastore.NewKey(reportAuditPrefix(review.CommunityID, review.ReportID) + fmt.Sprintf("%020d", order+1))
	if err := s.ds.Put(ctx, auditKey, encodedEntry); err != nil { return review, err }
	if err := s.ds.Put(ctx, stateKey, encodedState); err != nil { return review, err }
	if err := s.ds.Put(ctx, orderKey, []byte(strconv.FormatUint(order+1, 10))); err != nil { return review, err }
	if err := s.ds.Put(ctx, seqKey, []byte(strconv.FormatUint(review.Sequence, 10))); err != nil { return review, err }
	return review, nil
}

// ReportViews includes the persisted review status while preserving the reporter-signed report object.
func (s *Store) ReportViews(ctx context.Context, communityID, requesterID string, limit int) ([]ReportView, error) {
	reports, err := s.Reports(ctx, communityID, requesterID, limit)
	if err != nil { return nil, err }
	out := make([]ReportView, 0, len(reports))
	for _, report := range reports {
		view := ReportView{Report: report, Status: "open", UpdatedAt: report.CreatedAt}
		raw, getErr := s.ds.Get(ctx, reportStateKey(communityID, report.ReportID))
		if getErr == nil {
			var state ReportReviewState
			if json.Unmarshal(raw, &state) != nil { return nil, errors.New("stored report review state is corrupt") }
			view.Status, view.ReviewerPublicID, view.DecisionNote, view.UpdatedAt =
				state.Status, state.ReviewerPublicID, state.Note, state.UpdatedAt
		} else if !errors.Is(getErr, datastore.ErrNotFound) { return nil, getErr }
		out = append(out, view)
	}
	return out, nil
}

func (s *Store) ReportAudit(ctx context.Context, communityID, reportID, requesterID string, limit int) ([]ReportAuditEntry, error) {
	if !validCommunityID(communityID) || !validCommunityID(reportID) || limit < 1 || limit > MaxEventsPage {
		return nil, errors.New("invalid report audit query")
	}
	manifest, err := s.GetManifest(ctx, communityID, true)
	if err != nil { return nil, err }
	if manifest.Visibility == "private" && !s.CanReadPrivate(ctx, communityID, requesterID) {
		return nil, errors.New("private community audit access denied")
	}
	if !s.isCommunityModerator(ctx, manifest, requesterID) {
		return nil, errors.New("only the owner or a moderator may read report audit")
	}
	if _, err := s.ds.Get(ctx, datastore.NewKey(reportPrefix+strings.ToLower(communityID)+"/"+strings.ToLower(reportID))); err != nil {
		return nil, errors.New("report does not exist")
	}
	results, err := s.ds.Query(ctx, query.Query{Prefix: reportAuditPrefix(communityID, reportID), Limit: 1000})
	if err != nil { return nil, err }
	defer results.Close()
	out := make([]ReportAuditEntry, 0, limit)
	for entry := range results.Next() {
		if entry.Error != nil { return nil, entry.Error }
		var stored storedReportAuditEntry
		if json.Unmarshal(entry.Value, &stored) != nil || VerifyReportReview(stored.Review, s.clock()) != nil {
			return nil, errors.New("stored report audit record is corrupt")
		}
		out = append(out, ReportAuditEntry{Order: stored.Order, Review: stored.Review})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Order < out[j].Order })
	if len(out) > limit { out = out[:limit] }
	return out, nil
}
