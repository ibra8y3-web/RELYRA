package dhtdirectory

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"time"

	libp2pcrypto "github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"

	communities "github.com/ibra8y3-web/RELYRA/services/discovery/internal/community"
)

const (
	Version = 1
	TTL = 3 * time.Minute
	MaxRecords = 50
	MaxBytes = 1024 * 1024
	keyPrefix = "community-directory/v1/"
)

type Record struct {
	Version int `json:"version"`
	PeerID string `json:"peerId"`
	IssuedAt int64 `json:"issuedAt"`
	ExpiresAt int64 `json:"expiresAt"`
	Communities []communities.Manifest `json:"communities"`
	Signature string `json:"signature"`
}

func KeyForPeerID(rawPeerID string) (string, error) {
	id, err := peer.Decode(strings.TrimSpace(rawPeerID))
	if err != nil { return "", errors.New("invalid libp2p peer ID") }
	return "/rlyra/" + keyPrefix + id.String(), nil
}

func Encode(peerID peer.ID, privateKey libp2pcrypto.PrivKey, manifests []communities.Manifest, now time.Time) ([]byte, error) {
	if peerID == "" || privateKey == nil { return nil, errors.New("directory signer is unavailable") }
	derivedID, err := peer.IDFromPrivateKey(privateKey)
	if err != nil || derivedID != peerID { return nil, errors.New("directory signing key does not match peer ID") }
	if len(manifests) > MaxRecords { return nil, errors.New("too many public communities in directory snapshot") }

	entries := make([]communities.Manifest, 0, len(manifests))
	seen := make(map[string]struct{}, len(manifests))
	for _, manifest := range manifests {
		if manifest.Visibility != "public" { continue }
		if err := communities.VerifyManifest(manifest, now); err != nil {
			return nil, fmt.Errorf("invalid community %s in directory: %w", manifest.CommunityID, err)
		}
		id := strings.ToLower(manifest.CommunityID)
		if _, exists := seen[id]; exists { return nil, errors.New("duplicate community in directory snapshot") }
		seen[id] = struct{}{}
		entries = append(entries, manifest)
	}
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].UpdatedAt == entries[j].UpdatedAt {
			return strings.ToLower(entries[i].CommunityID) < strings.ToLower(entries[j].CommunityID)
		}
		return entries[i].UpdatedAt > entries[j].UpdatedAt
	})

	record := Record{
		Version: Version,
		PeerID: peerID.String(),
		IssuedAt: now.Unix(),
		ExpiresAt: now.Add(TTL).Unix(),
		Communities: entries,
	}
	payload, err := record.signingBytes()
	if err != nil { return nil, err }
	signature, err := privateKey.Sign(payload)
	if err != nil { return nil, err }
	record.Signature = base64.RawURLEncoding.EncodeToString(signature)
	raw, err := json.Marshal(record)
	if err != nil { return nil, err }
	if len(raw) > MaxBytes { return nil, errors.New("community directory snapshot is too large") }
	return raw, nil
}

func Parse(raw []byte, expectedPeerID string, now time.Time) (Record, error) {
	var record Record
	if len(raw) == 0 || len(raw) > MaxBytes { return record, errors.New("community directory size is out of range") }
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&record); err != nil { return record, errors.New("invalid community directory JSON") }
	if err := decoder.Decode(new(any)); err != io.EOF { return record, errors.New("trailing community directory data") }
	if record.Version != Version || !strings.EqualFold(record.PeerID, expectedPeerID) {
		return record, errors.New("community directory identity or version mismatch")
	}
	if record.IssuedAt <= 0 || record.IssuedAt > now.Unix()+60 ||
		record.ExpiresAt <= now.Unix() || record.ExpiresAt <= record.IssuedAt ||
		record.ExpiresAt-record.IssuedAt > int64(TTL.Seconds()) {
		return record, errors.New("community directory lease is invalid or expired")
	}
	if len(record.Communities) > MaxRecords { return record, errors.New("too many communities in directory snapshot") }
	id, err := peer.Decode(record.PeerID)
	if err != nil { return record, errors.New("invalid directory peer ID") }
	publicKey, err := id.ExtractPublicKey()
	if err != nil || publicKey == nil { return record, errors.New("directory peer ID does not embed a verifiable public key") }
	signature, err := base64.RawURLEncoding.DecodeString(record.Signature)
	if err != nil || len(signature) != 64 { return record, errors.New("invalid directory signature encoding") }
	payload, err := record.signingBytes()
	if err != nil { return record, err }
	valid, err := publicKey.Verify(payload, signature)
	if err != nil || !valid { return record, errors.New("invalid directory node signature") }

	seen := make(map[string]struct{}, len(record.Communities))
	for _, manifest := range record.Communities {
		id := strings.ToLower(manifest.CommunityID)
		if manifest.Visibility != "public" { return record, errors.New("private community must not appear in public directory") }
		if _, exists := seen[id]; exists { return record, errors.New("duplicate community in directory snapshot") }
		seen[id] = struct{}{}
		if err := communities.VerifyManifest(manifest, now); err != nil {
			return record, fmt.Errorf("invalid owner-signed community manifest: %w", err)
		}
	}
	return record, nil
}

func Validate(key string, raw []byte) (Record, error) {
	key = strings.TrimPrefix(strings.TrimPrefix(key, "/"), "rlyra/")
	if !strings.HasPrefix(key, keyPrefix) { return Record{}, errors.New("invalid community directory DHT key") }
	peerID := strings.TrimPrefix(key, keyPrefix)
	if peerID == "" || strings.Contains(peerID, "/") { return Record{}, errors.New("invalid community directory DHT key") }
	return Parse(raw, peerID, time.Now())
}

func (r Record) signingBytes() ([]byte, error) {
	if r.Version != Version || r.PeerID == "" || r.IssuedAt <= 0 || r.ExpiresAt <= r.IssuedAt {
		return nil, errors.New("invalid community directory signing fields")
	}
	unsigned := r
	unsigned.Signature = ""
	return json.Marshal(unsigned)
}
