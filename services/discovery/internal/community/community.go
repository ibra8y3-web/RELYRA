package community

import (
	"bytes"
	"io"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	datastore "github.com/ipfs/go-datastore"
	"github.com/ipfs/go-datastore/query"

	identity "github.com/ibra8y3-web/RELYRA/services/discovery/internal/protocol"
)

const (
	ManifestVersion = 1
	MaxManifestBytes = 12 * 1024
	MaxEventBytes = 8 * 1024
	MaxReportBytes = 4 * 1024
	MaxModerators = 32
	MaxDiscoverPage = 50
	MaxEventsPage = 100
	manifestPrefix = "/relyra/community/manifest/"
	publicIndexPrefix = "/relyra/community/public/"
	eventPrefix = "/relyra/community/event/"
	sequencePrefix = "/relyra/community/sequence/"
	reportPrefix = "/relyra/community/report/"
	manifestDomain = "RELYRA-COMMUNITY-MANIFEST-V1"
	eventDomain = "RELYRA-COMMUNITY-MODERATION-V1"
	reportDomain = "RELYRA-COMMUNITY-REPORT-V1"
)

var (
	communityIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	reasonPattern = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)
	safeTokenPattern = regexp.MustCompile(`^[a-z0-9_-]{1,48}$`)
)

type Manifest struct {
	Version int `json:"version"`
	CommunityID string `json:"communityId"`
	OwnerPublicID string `json:"ownerPublicId"`
	Name string `json:"name"`
	Description string `json:"description"`
	Visibility string `json:"visibility"` // public or private
	ModeratorPublicIDs []string `json:"moderatorPublicIds"`
	PolicyVersion uint64 `json:"policyVersion"`
	CreatedAt int64 `json:"createdAt"`
	UpdatedAt int64 `json:"updatedAt"`
	Signature string `json:"signature"`
}

type ModerationEvent struct {
	Version int `json:"version"`
	EventID string `json:"eventId"`
	CommunityID string `json:"communityId"`
	ActorPublicID string `json:"actorPublicId"`
	Sequence uint64 `json:"sequence"`
	PolicyVersion uint64 `json:"policyVersion"`
	Action string `json:"action"`
	SubjectPublicID string `json:"subjectPublicId"`
	ReasonCode string `json:"reasonCode"`
	CreatedAt int64 `json:"createdAt"`
	ExpiresAt int64 `json:"expiresAt,omitempty"`
	Signature string `json:"signature"`
}

type Report struct {
	Version int `json:"version"`
	ReportID string `json:"reportId"`
	CommunityID string `json:"communityId"`
	ReporterPublicID string `json:"reporterPublicId"`
	Category string `json:"category"`
	Summary string `json:"summary"`
	CreatedAt int64 `json:"createdAt"`
	Signature string `json:"signature"`
}

type Store struct {
	ds datastore.Datastore
	mu sync.Mutex
	clock func() time.Time
}

func NewStore(ds datastore.Datastore) *Store {
	return &Store{ds: ds, clock: time.Now}
}

func NewStoreWithClock(ds datastore.Datastore, clock func() time.Time) *Store {
	return &Store{ds: ds, clock: clock}
}

func validCommunityID(id string) bool {
	return communityIDPattern.MatchString(strings.ToLower(strings.TrimSpace(id)))
}

func identityKey(publicID string) (ed25519.PublicKey, error) {
	key, err := identity.DecodePublicID(publicID)
	if err != nil { return nil, errors.New("invalid RELYRA public identity") }
	return key, nil
}

func canonicalManifest(m Manifest) ([]byte, error) {
	if m.Version != ManifestVersion || !validCommunityID(m.CommunityID) {
		return nil, errors.New("invalid community manifest version or ID")
	}
	if _, err := identityKey(m.OwnerPublicID); err != nil { return nil, err }
	if m.Visibility != "public" && m.Visibility != "private" {
		return nil, errors.New("visibility must be public or private")
	}
	if strings.TrimSpace(m.Name) == "" || len(m.Name) > 80 || strings.ContainsAny(m.Name, "\r\n\x00") {
		return nil, errors.New("community name is invalid")
	}
	if len(m.Description) > 1000 || strings.ContainsRune(m.Description, '\x00') {
		return nil, errors.New("community description is too long or invalid")
	}
	if m.PolicyVersion == 0 || m.CreatedAt <= 0 || m.UpdatedAt < m.CreatedAt {
		return nil, errors.New("invalid community policy version or timestamps")
	}
	if len(m.ModeratorPublicIDs) > MaxModerators {
		return nil, errors.New("too many moderators")
	}
	mods := append([]string(nil), m.ModeratorPublicIDs...)
	for _, id := range mods {
		if _, err := identityKey(id); err != nil { return nil, errors.New("invalid moderator identity") }
	}
	sort.Strings(mods)
	for i := 1; i < len(mods); i++ {
		if strings.EqualFold(mods[i], mods[i-1]) { return nil, errors.New("duplicate moderator") }
	}
	return []byte(strings.Join([]string{
		manifestDomain,
		strings.ToLower(m.CommunityID),
		strings.ToLower(m.OwnerPublicID),
		m.Name,
		m.Description,
		m.Visibility,
		strings.Join(mods, ","),
		strconv.FormatUint(m.PolicyVersion, 10),
		strconv.FormatInt(m.CreatedAt, 10),
		strconv.FormatInt(m.UpdatedAt, 10),
	}, "\n")), nil
}

func VerifyManifest(m Manifest, now time.Time) error {
	payload, err := canonicalManifest(m)
	if err != nil { return err }
	if time.UnixMilli(m.CreatedAt).After(now.Add(5*time.Minute)) ||
		time.UnixMilli(m.UpdatedAt).After(now.Add(5*time.Minute)) ||
		now.Sub(time.UnixMilli(m.CreatedAt)) > 3650*24*time.Hour {
		return errors.New("community manifest timestamps are outside the accepted range")
	}
	publicKey, err := identityKey(m.OwnerPublicID)
	if err != nil { return err }
	signature, err := base64.RawURLEncoding.DecodeString(m.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize ||
		!ed25519.Verify(publicKey, payload, signature) {
		return errors.New("invalid community owner signature")
	}
	return nil
}

func canonicalEvent(e ModerationEvent) ([]byte, error) {
	if e.Version != ManifestVersion || !validCommunityID(e.CommunityID) ||
		!communityIDPattern.MatchString(strings.ToLower(e.EventID)) {
		return nil, errors.New("invalid moderation event ID or version")
	}
	if _, err := identityKey(e.ActorPublicID); err != nil { return nil, err }
	if _, err := identityKey(e.SubjectPublicID); err != nil { return nil, err }
	if e.Sequence == 0 || e.PolicyVersion == 0 || e.CreatedAt <= 0 ||
		(e.ExpiresAt != 0 && e.ExpiresAt <= e.CreatedAt) {
		return nil, errors.New("invalid moderation sequence, policy or timestamps")
	}
	switch e.Action {
	case "ban", "mute", "slow_mode", "remove_member", "allow_post":
	default:
		return nil, errors.New("unsupported moderation action")
	}
	if !reasonPattern.MatchString(e.ReasonCode) {
		return nil, errors.New("invalid moderation reason code")
	}
	if strings.EqualFold(e.ActorPublicID, e.SubjectPublicID) {
		return nil, errors.New("an actor cannot moderate itself")
	}
	return []byte(strings.Join([]string{
		eventDomain,
		strings.ToLower(e.EventID),
		strings.ToLower(e.CommunityID),
		strings.ToLower(e.ActorPublicID),
		strconv.FormatUint(e.Sequence, 10),
		strconv.FormatUint(e.PolicyVersion, 10),
		e.Action,
		strings.ToLower(e.SubjectPublicID),
		e.ReasonCode,
		strconv.FormatInt(e.CreatedAt, 10),
		strconv.FormatInt(e.ExpiresAt, 10),
	}, "\n")), nil
}

func VerifyModerationEvent(e ModerationEvent, m Manifest, now time.Time) error {
	if err := VerifyManifest(m, now); err != nil { return err }
	if !strings.EqualFold(e.CommunityID, m.CommunityID) || e.PolicyVersion != m.PolicyVersion {
		return errors.New("moderation event does not match current community policy")
	}
	issued := time.UnixMilli(e.CreatedAt)
	if issued.After(now.Add(5*time.Minute)) || now.Sub(issued) > 30*24*time.Hour {
		return errors.New("moderation event is outside its accepted issue window")
	}
	if e.ExpiresAt != 0 && !time.UnixMilli(e.ExpiresAt).After(now) {
		return errors.New("moderation event has expired")
	}
	isOwner := strings.EqualFold(e.ActorPublicID, m.OwnerPublicID)
	isModerator := false
	for _, id := range m.ModeratorPublicIDs {
		if strings.EqualFold(id, e.ActorPublicID) { isModerator = true; break }
	}
	if !isOwner && !isModerator { return errors.New("actor is not an authorized community moderator") }
	if !isOwner && (e.Action == "remove_member" || e.Action == "allow_post") {
		return errors.New("only the community owner may remove members or change posting permissions")
	}
	payload, err := canonicalEvent(e)
	if err != nil { return err }
	publicKey, err := identityKey(e.ActorPublicID)
	if err != nil { return err }
	signature, err := base64.RawURLEncoding.DecodeString(e.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize ||
		!ed25519.Verify(publicKey, payload, signature) {
		return errors.New("invalid moderation event signature")
	}
	return nil
}

func canonicalReport(r Report) ([]byte, error) {
	if r.Version != ManifestVersion || !validCommunityID(r.CommunityID) ||
		!communityIDPattern.MatchString(strings.ToLower(r.ReportID)) {
		return nil, errors.New("invalid report ID or version")
	}
	if _, err := identityKey(r.ReporterPublicID); err != nil { return nil, err }
	if !safeTokenPattern.MatchString(r.Category) || len(strings.TrimSpace(r.Summary)) == 0 ||
		len(r.Summary) > 512 || strings.ContainsRune(r.Summary, '\x00') {
		return nil, errors.New("invalid report category or summary")
	}
	if r.CreatedAt <= 0 { return nil, errors.New("invalid report timestamp") }
	return []byte(strings.Join([]string{
		reportDomain, strings.ToLower(r.ReportID), strings.ToLower(r.CommunityID),
		strings.ToLower(r.ReporterPublicID), r.Category, r.Summary, strconv.FormatInt(r.CreatedAt, 10),
	}, "\n")), nil
}

func VerifyReport(r Report, now time.Time) error {
	payload, err := canonicalReport(r)
	if err != nil { return err }
	created := time.UnixMilli(r.CreatedAt)
	if created.After(now.Add(5*time.Minute)) || now.Sub(created) > 30*24*time.Hour {
		return errors.New("report timestamp is outside the accepted window")
	}
	publicKey, err := identityKey(r.ReporterPublicID)
	if err != nil { return err }
	signature, err := base64.RawURLEncoding.DecodeString(r.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize ||
		!ed25519.Verify(publicKey, payload, signature) {
		return errors.New("invalid report signature")
	}
	return nil
}

func (s *Store) PublishManifest(ctx context.Context, raw []byte) (Manifest, error) {
	var m Manifest
	if len(raw) == 0 || len(raw) > MaxManifestBytes { return m, errors.New("manifest is oversized") }
	if err := decodeStrict(raw, &m); err != nil { return m, errors.New("invalid manifest JSON") }
	if err := VerifyManifest(m, s.clock()); err != nil { return m, err }
	s.mu.Lock()
	defer s.mu.Unlock()
	key := datastore.NewKey(manifestPrefix + strings.ToLower(m.CommunityID))
	existingRaw, err := s.ds.Get(ctx, key)
	if err == nil {
		var existing Manifest
		if json.Unmarshal(existingRaw, &existing) != nil { return m, errors.New("stored manifest is corrupt") }
		if !strings.EqualFold(existing.OwnerPublicID, m.OwnerPublicID) { return m, errors.New("community owner cannot be changed") }
		if m.PolicyVersion <= existing.PolicyVersion || m.UpdatedAt <= existing.UpdatedAt {
			return m, errors.New("manifest update must increase policy version and timestamp")
		}
	} else if !errors.Is(err, datastore.ErrNotFound) {
		return m, err
	} else if m.PolicyVersion != 1 {
		return m, errors.New("new communities must start at policy version 1")
	}
	encoded, err := json.Marshal(m)
	if err != nil { return m, err }
	if err := s.ds.Put(ctx, key, encoded); err != nil { return m, err }
	indexKey := datastore.NewKey(publicIndexPrefix + strings.ToLower(m.CommunityID))
	if m.Visibility == "public" {
		if err := s.ds.Put(ctx, indexKey, encoded); err != nil { return m, err }
	} else if err := s.ds.Delete(ctx, indexKey); err != nil && !errors.Is(err, datastore.ErrNotFound) {
		return m, err
	}
	return m, nil
}

func (s *Store) GetManifest(ctx context.Context, communityID string, allowPrivate bool) (Manifest, error) {
	var m Manifest
	if !validCommunityID(communityID) { return m, errors.New("invalid community ID") }
	raw, err := s.ds.Get(ctx, datastore.NewKey(manifestPrefix+strings.ToLower(communityID)))
	if err != nil { return m, err }
	if err := json.Unmarshal(raw, &m); err != nil { return m, errors.New("stored manifest is corrupt") }
	if err := VerifyManifest(m, s.clock()); err != nil { return m, err }
	if m.Visibility != "public" && !allowPrivate { return m, datastore.ErrNotFound }
	return m, nil
}

func (s *Store) Discover(ctx context.Context, limit int) ([]Manifest, error) {
	if limit < 1 || limit > MaxDiscoverPage { return nil, errors.New("invalid discovery page size") }
	results, err := s.ds.Query(ctx, query.Query{Prefix: publicIndexPrefix})
	if err != nil { return nil, err }
	defer results.Close()
	out := make([]Manifest, 0, limit)
	for entry := range results.Next() {
		if entry.Error != nil { return nil, entry.Error }
		var m Manifest
		if json.Unmarshal(entry.Value, &m) != nil || m.Visibility != "public" { continue }
		if VerifyManifest(m, s.clock()) != nil { continue }
		out = append(out, m)
		if len(out) >= limit { break }
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt > out[j].UpdatedAt })
	return out, nil
}

func (s *Store) LastSequence(ctx context.Context, communityID, actorPublicID string) (uint64, error) {
	if !validCommunityID(communityID) { return 0, errors.New("invalid community ID") }
	if _, err := identityKey(actorPublicID); err != nil { return 0, err }
	raw, err := s.ds.Get(ctx, datastore.NewKey(sequenceStorageKey(communityID, actorPublicID)))
	if errors.Is(err, datastore.ErrNotFound) { return 0, nil }
	if err != nil { return 0, err }
	value, err := strconv.ParseUint(string(raw), 10, 64)
	if err != nil { return 0, errors.New("stored moderation sequence is corrupt") }
	return value, nil
}

func eventStorageKey(id, actor string, sequence uint64) string {
	return fmt.Sprintf("%s%s/%s/%020d", eventPrefix, strings.ToLower(id), strings.ToLower(actor), sequence)
}

func sequenceStorageKey(id, actor string) string {
	return sequencePrefix + strings.ToLower(id) + "/" + strings.ToLower(actor)
}

func (s *Store) AppendModerationEvent(ctx context.Context, raw []byte) (ModerationEvent, error) {
	var e ModerationEvent
	if len(raw) == 0 || len(raw) > MaxEventBytes { return e, errors.New("moderation event is oversized") }
	if err := decodeStrict(raw, &e); err != nil { return e, errors.New("invalid moderation event JSON") }
	s.mu.Lock()
	defer s.mu.Unlock()
	m, err := s.GetManifest(ctx, e.CommunityID, true)
	if err != nil { return e, err }
	if err := VerifyModerationEvent(e, m, s.clock()); err != nil { return e, err }
	seqKey := datastore.NewKey(sequenceStorageKey(e.CommunityID, e.ActorPublicID))
	rawSeq, err := s.ds.Get(ctx, seqKey)
	var last uint64
	if err == nil {
		last, err = strconv.ParseUint(string(rawSeq), 10, 64)
		if err != nil { return e, errors.New("stored moderation sequence is corrupt") }
	} else if !errors.Is(err, datastore.ErrNotFound) { return e, err }
	if e.Sequence != last+1 { return e, errors.New("moderation sequence is stale, duplicated or has a gap") }
	encoded, err := json.Marshal(e)
	if err != nil { return e, err }
	if err := s.ds.Put(ctx, datastore.NewKey(eventStorageKey(e.CommunityID, e.ActorPublicID, e.Sequence)), encoded); err != nil {
		return e, err
	}
	if err := s.ds.Put(ctx, seqKey, []byte(strconv.FormatUint(e.Sequence, 10))); err != nil {
		return e, err
	}
	return e, nil
}

func (s *Store) Events(ctx context.Context, communityID string, limit int) ([]ModerationEvent, error) {
	if !validCommunityID(communityID) || limit < 1 || limit > MaxEventsPage { return nil, errors.New("invalid events query") }
	if _, err := s.GetManifest(ctx, communityID, false); err != nil { return nil, err }
	results, err := s.ds.Query(ctx, query.Query{Prefix: eventPrefix + strings.ToLower(communityID) + "/"})
	if err != nil { return nil, err }
	defer results.Close()
	out := make([]ModerationEvent, 0, limit)
	for entry := range results.Next() {
		if entry.Error != nil { return nil, entry.Error }
		var event ModerationEvent
		if json.Unmarshal(entry.Value, &event) != nil { continue }
		out = append(out, event)
		if len(out) >= limit { break }
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt == out[j].CreatedAt { return out[i].Sequence < out[j].Sequence }
		return out[i].CreatedAt < out[j].CreatedAt
	})
	return out, nil
}

func (s *Store) SubmitReport(ctx context.Context, raw []byte) (Report, error) {
	var report Report
	if len(raw) == 0 || len(raw) > MaxReportBytes { return report, errors.New("report is oversized") }
	if err := decodeStrict(raw, &report); err != nil { return report, errors.New("invalid report JSON") }
	if err := VerifyReport(report, s.clock()); err != nil { return report, err }
	// Reports deliberately have no message-content, attachment, URL or recipient fields.
	// The report is opt-in and includes only a controlled category and a short summary.
	if _, err := s.GetManifest(ctx, report.CommunityID, true); err != nil { return report, err }
	s.mu.Lock()
	defer s.mu.Unlock()
	key := datastore.NewKey(reportPrefix + strings.ToLower(report.CommunityID) + "/" + strings.ToLower(report.ReportID))
	if exists, err := s.ds.Has(ctx, key); err != nil { return report, err } else if exists {
		return report, errors.New("report already exists")
	}
	encoded, err := json.Marshal(report)
	if err != nil { return report, err }
	if err := s.ds.Put(ctx, key, encoded); err != nil { return report, err }
	return report, nil
}

func decodeStrict(raw []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil { return err }
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil { return errors.New("multiple JSON values are not allowed") }
		return err
	}
	return nil
}

func NewReportID() (string, error) {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil { return "", err }
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		raw[0:4], raw[4:6], raw[6:8], raw[8:10], raw[10:16]), nil
}

func Fingerprint(publicID string) (string, error) {
	key, err := identityKey(publicID)
	if err != nil { return "", err }
	sum := sha256.Sum256(key)
	return hex.EncodeToString(sum[:16]), nil
}

