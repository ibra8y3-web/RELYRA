package mailbox

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"github.com/ipfs/go-datastore/query"
	"sort"
	"strings"
	"sync"
	"time"

	datastore "github.com/ipfs/go-datastore"

	"github.com/ibra8y3-web/RELYRA/services/discovery/internal/protocol"
)

const (
	Version = 1
	MaxPayloadBytes = 64 * 1024
	MaxEnvelopeBytes = 96 * 1024
	MaxPendingMessages = 256
	MaxPendingBytes = 16 * 1024 * 1024
	MaxListPage = 20
	MaxTTL = 24 * time.Hour
	mailboxPrefix = "/relyra/mailbox/"
	signingDomain = "RELYRA-STORE-FORWARD-V1"
)

var (
	ErrQuotaExceeded = errors.New("mailbox quota exceeded")
	ErrConflict = errors.New("mailbox message ID conflicts with stored content")
	ErrNotFound = errors.New("mailbox message not found")
	ErrUnauthorized = errors.New("mailbox recipient does not match the authenticated identity")
	messageIDPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

type Envelope struct {
	Version int `json:"version"`
	MessageID string `json:"messageId"`
	SenderPublicID string `json:"senderPublicId"`
	RecipientPublicID string `json:"recipientPublicId"`
	IssuedAt int64 `json:"issuedAt"`
	ExpiresAt int64 `json:"expiresAt"`
	Payload string `json:"payload"`
	Digest string `json:"digest"`
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

func (s *Store) SetClockForTest(clock func() time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.clock = clock
}

func (e Envelope) signingBytes() []byte {
	return []byte(strings.Join([]string{
		signingDomain,
		strings.ToLower(e.MessageID),
		strings.ToLower(e.SenderPublicID),
		strings.ToLower(e.RecipientPublicID),
		fmt.Sprint(e.IssuedAt),
		fmt.Sprint(e.ExpiresAt),
		strings.ToLower(e.Digest),
	}, "\n"))
}

func parseAndVerify(raw []byte, now time.Time) (Envelope, []byte, error) {
	var envelope Envelope
	if len(raw) == 0 || len(raw) > MaxEnvelopeBytes { return envelope, nil, errors.New("mailbox envelope size is out of range") }
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&envelope); err != nil { return envelope, nil, errors.New("invalid mailbox envelope JSON") }
	if err := decoder.Decode(new(any)); err != io.EOF { return envelope, nil, errors.New("trailing mailbox envelope data") }
	if envelope.Version != Version || !messageIDPattern.MatchString(envelope.MessageID) ||
		envelope.MessageID != strings.ToLower(envelope.MessageID) ||
		envelope.Digest != envelope.MessageID {
		return envelope, nil, errors.New("invalid mailbox message ID or version")
	}
	sender := strings.ToLower(envelope.SenderPublicID)
	recipient := strings.ToLower(envelope.RecipientPublicID)
	if _, err := protocol.DecodePublicID(sender); err != nil { return envelope, nil, errors.New("invalid mailbox sender identity") }
	if _, err := protocol.DecodePublicID(recipient); err != nil { return envelope, nil, errors.New("invalid mailbox recipient identity") }
	if sender == recipient { return envelope, nil, errors.New("mailbox sender and recipient must differ") }
	if envelope.IssuedAt <= 0 || envelope.IssuedAt > now.Unix()+60 ||
		now.Unix()-envelope.IssuedAt > 5*60 ||
		envelope.ExpiresAt <= now.Unix() || envelope.ExpiresAt <= envelope.IssuedAt ||
		envelope.ExpiresAt-envelope.IssuedAt > int64(MaxTTL.Seconds()) {
		return envelope, nil, errors.New("mailbox envelope timestamp or TTL is invalid")
	}
	payload, err := base64.RawURLEncoding.DecodeString(envelope.Payload)
	if err != nil || len(payload) == 0 || len(payload) > MaxPayloadBytes {
		return envelope, nil, errors.New("mailbox payload size or encoding is invalid")
	}
	digest := sha256.Sum256(payload)
	if hex.EncodeToString(digest[:]) != envelope.Digest { return envelope, nil, errors.New("mailbox payload digest mismatch") }
	publicKey, err := protocol.DecodePublicID(sender)
	if err != nil { return envelope, nil, errors.New("invalid mailbox sender key") }
	signature, err := base64.RawURLEncoding.DecodeString(envelope.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize ||
		!ed25519.Verify(publicKey, envelope.signingBytes(), signature) {
		return envelope, nil, errors.New("invalid mailbox sender signature")
	}
	envelope.SenderPublicID = sender
	envelope.RecipientPublicID = recipient
	return envelope, payload, nil
}

// Put stores only a sender-signed, end-to-end encrypted wire payload. The stable
// digest-derived ID makes an identical retry idempotent without extending its TTL.
func (s *Store) Put(ctx context.Context, raw []byte) (bool, error) {
	now := s.clock()
	envelope, _, err := parseAndVerify(raw, now)
	if err != nil { return false, err }
	s.mu.Lock()
	defer s.mu.Unlock()

	key := datastore.NewKey(mailboxPrefix + envelope.RecipientPublicID + "/" + envelope.MessageID)
	existing, err := s.ds.Get(ctx, key)
	if err == nil {
		prior, _, parseErr := parseAndVerify(existing, now)
		if parseErr == nil && prior.MessageID == envelope.MessageID &&
			prior.SenderPublicID == envelope.SenderPublicID &&
			prior.RecipientPublicID == envelope.RecipientPublicID &&
			prior.Digest == envelope.Digest {
			return false, nil
		}
		return false, ErrConflict
	}
	if !errors.Is(err, datastore.ErrNotFound) { return false, fmt.Errorf("read mailbox item: %w", err) }

	results, err := s.ds.Query(ctx, queryForRecipient(envelope.RecipientPublicID))
	if err != nil { return false, fmt.Errorf("query recipient mailbox: %w", err) }
	count, totalBytes := 0, 0
	for entry := range results.Next() {
		if entry.Error != nil { _ = results.Close(); return false, entry.Error }
		prior, _, parseErr := parseAndVerify(entry.Value, now)
		if parseErr != nil || prior.ExpiresAt <= now.Unix() {
			_ = s.ds.Delete(ctx, entry.Key)
			continue
		}
		count++
		totalBytes += len(entry.Value)
	}
	_ = results.Close()
	if count >= MaxPendingMessages || totalBytes+len(raw) > MaxPendingBytes { return false, ErrQuotaExceeded }
	if err := s.ds.Put(ctx, key, raw); err != nil { return false, fmt.Errorf("store mailbox envelope: %w", err) }
	return true, nil
}

func (s *Store) List(ctx context.Context, recipient string, limit int) ([]Envelope, error) {
	recipient = strings.ToLower(strings.TrimSpace(recipient))
	if _, err := protocol.DecodePublicID(recipient); err != nil { return nil, errors.New("invalid mailbox recipient identity") }
	if limit < 1 || limit > MaxListPage { return nil, errors.New("invalid mailbox page size") }
	s.mu.Lock()
	defer s.mu.Unlock()
	results, err := s.ds.Query(ctx, queryForRecipient(recipient))
	if err != nil { return nil, fmt.Errorf("query recipient mailbox: %w", err) }
	defer results.Close()
	items := make([]Envelope, 0, limit)
	now := s.clock()
	for entry := range results.Next() {
		if entry.Error != nil { return nil, entry.Error }
		envelope, _, parseErr := parseAndVerify(entry.Value, now)
		if parseErr != nil || envelope.ExpiresAt <= now.Unix() {
			_ = s.ds.Delete(ctx, entry.Key)
			continue
		}
		items = append(items, envelope)
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].IssuedAt == items[j].IssuedAt { return items[i].MessageID < items[j].MessageID }
		return items[i].IssuedAt < items[j].IssuedAt
	})
	if len(items) > limit { items = items[:limit] }
	return items, nil
}

func (s *Store) Delete(ctx context.Context, recipient, messageID string) error {
	recipient = strings.ToLower(strings.TrimSpace(recipient))
	if _, err := protocol.DecodePublicID(recipient); err != nil { return ErrUnauthorized }
	if !messageIDPattern.MatchString(messageID) { return ErrNotFound }
	s.mu.Lock()
	defer s.mu.Unlock()
	key := datastore.NewKey(mailboxPrefix + recipient + "/" + messageID)
	raw, err := s.ds.Get(ctx, key)
	if errors.Is(err, datastore.ErrNotFound) { return ErrNotFound }
	if err != nil { return fmt.Errorf("read mailbox item: %w", err) }
	envelope, _, err := parseAndVerify(raw, s.clock())
	if err != nil || envelope.RecipientPublicID != recipient { return ErrUnauthorized }
	if err := s.ds.Delete(ctx, key); err != nil { return fmt.Errorf("delete mailbox item: %w", err) }
	return nil
}

func queryForRecipient(recipient string) query.Query {
	return query.Query{Prefix: mailboxPrefix + recipient + "/"}
}
