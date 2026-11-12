package protocol

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"encoding/json"
	"bytes"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	RecordVersion     = 1
	RecordTTL         = 3 * time.Minute
	MaxRecordBytes    = 8 * 1024
	MaxCapabilities   = 4
	recordDomain      = "RELYRA-DHT-PRESENCE-V1"
	peerKeyPrefix     = "/rlyra/peer/v1/"
)

var allowedCapabilities = map[string]struct{}{
	"webrtc-data-v1": {},
	"nostr-signal-v1": {},
}

type PeerRecord struct {
	Version      int      `json:"version"`
	PublicID     string   `json:"publicId"`
	PublicKey    string   `json:"publicKey"`
	IssuedAt     int64    `json:"issuedAt"`
	ExpiresAt    int64    `json:"expiresAt"`
	Capabilities []string `json:"capabilities"`
	Signature    string   `json:"signature"`
}

func KeyForPublicID(publicID string) (string, error) {
	if _, err := DecodePublicID(publicID); err != nil {
		return "", err
	}
	return peerKeyPrefix + strings.ToLower(publicID), nil
}

func DecodePublicID(publicID string) (ed25519.PublicKey, error) {
	id := strings.ToLower(strings.TrimSpace(publicID))
	if !strings.HasPrefix(id, "rly1") || len(id) > 128 {
		return nil, errors.New("invalid RELYRA public ID")
	}
	enc := base32.StdEncoding.WithPadding(base32.NoPadding)
	raw, err := enc.DecodeString(strings.ToUpper(strings.TrimPrefix(id, "rly1")))
	if err != nil || len(raw) != ed25519.PublicKeySize+2 {
		return nil, errors.New("invalid RELYRA public ID encoding")
	}
	publicKey := raw[:ed25519.PublicKeySize]
	sum := sha256.Sum256(publicKey)
	if raw[ed25519.PublicKeySize] != sum[0] || raw[ed25519.PublicKeySize+1] != sum[1] {
		return nil, errors.New("invalid RELYRA public ID checksum")
	}
	return append(ed25519.PublicKey(nil), publicKey...), nil
}

func PublicIDForKey(publicKey ed25519.PublicKey) string {
	sum := sha256.Sum256(publicKey)
	raw := append(append([]byte(nil), publicKey...), sum[0], sum[1])
	return "rly1" + strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(raw))
}

func (r PeerRecord) signingBytes() ([]byte, error) {
	if r.Version != RecordVersion {
		return nil, errors.New("unsupported record version")
	}
	publicKey, err := DecodePublicID(r.PublicID)
	if err != nil {
		return nil, err
	}
	providedKey, err := base64.RawURLEncoding.DecodeString(r.PublicKey)
	if err != nil || len(providedKey) != ed25519.PublicKeySize || !bytes.Equal(providedKey, publicKey) {
		return nil, errors.New("public key does not match RELYRA ID")
	}
	if r.IssuedAt <= 0 || r.ExpiresAt <= r.IssuedAt || r.ExpiresAt-r.IssuedAt > int64(RecordTTL.Seconds()) {
		return nil, errors.New("invalid record lifetime")
	}
	capabilities := append([]string(nil), r.Capabilities...)
	if len(capabilities) == 0 || len(capabilities) > MaxCapabilities {
		return nil, errors.New("invalid capabilities")
	}
	sort.Strings(capabilities)
	for i, capability := range capabilities {
		if _, ok := allowedCapabilities[capability]; !ok || (i > 0 && capability == capabilities[i-1]) {
			return nil, errors.New("unsupported or duplicate capability")
		}
	}
	return []byte(strings.Join([]string{
		recordDomain,
		strings.ToLower(r.PublicID),
		r.PublicKey,
		strconv.FormatInt(r.IssuedAt, 10),
		strconv.FormatInt(r.ExpiresAt, 10),
		strings.Join(capabilities, ","),
	}, "\n")), nil
}

func (r PeerRecord) Validate(now time.Time) error {
	if len(r.Signature) > 128 {
		return errors.New("invalid signature size")
	}
	publicKey, err := DecodePublicID(r.PublicID)
	if err != nil {
		return err
	}
	payload, err := r.signingBytes()
	if err != nil {
		return err
	}
	issued := time.Unix(r.IssuedAt, 0)
	expires := time.Unix(r.ExpiresAt, 0)
	if issued.After(now.Add(60*time.Second)) || !expires.After(now) {
		return errors.New("record is not currently valid")
	}
	signature, err := base64.RawURLEncoding.DecodeString(r.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize || !ed25519.Verify(publicKey, payload, signature) {
		return errors.New("invalid RELYRA record signature")
	}
	return nil
}

func ParsePeerRecord(raw []byte, now time.Time) (PeerRecord, error) {
	var record PeerRecord
	if len(raw) == 0 || len(raw) > MaxRecordBytes {
		return record, errors.New("record size is out of range")
	}
	if err := json.Unmarshal(raw, &record); err != nil {
		return record, fmt.Errorf("invalid record JSON: %w", err)
	}
	if err := record.Validate(now); err != nil {
		return record, err
	}
	return record, nil
}

func EncodePeerRecord(record PeerRecord) ([]byte, error) {
	if err := record.Validate(time.Now()); err != nil {
		return nil, err
	}
	raw, err := json.Marshal(record)
	if err != nil {
		return nil, err
	}
	if len(raw) > MaxRecordBytes {
		return nil, errors.New("record is too large")
	}
	return raw, nil
}
