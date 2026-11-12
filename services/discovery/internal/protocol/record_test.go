package protocol

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"strings"
	"testing"
	"time"
)

func testIdentity(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey, string) {
	t.Helper()
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(publicKey)
	raw := append(append([]byte(nil), publicKey...), sum[0], sum[1])
	id := "rly1" + strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(raw))
	return publicKey, privateKey, id
}

func signedRecordForIdentity(t *testing.T, now time.Time, offset time.Duration, publicKey ed25519.PublicKey, privateKey ed25519.PrivateKey, id string) PeerRecord {
	t.Helper()
	issued := now.Add(offset).Unix()
	record := PeerRecord{
		Version: RecordVersion,
		PublicID: id,
		PublicKey: base64.RawURLEncoding.EncodeToString(publicKey),
		IssuedAt: issued,
		ExpiresAt: now.Add(offset).Add(RecordTTL).Unix(),
		Capabilities: []string{"webrtc-data-v1", "nostr-signal-v1"},
	}
	payload, err := record.signingBytes()
	if err != nil {
		t.Fatal(err)
	}
	record.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(privateKey, payload))
	return record
}

func signedRecord(t *testing.T, now time.Time, offset time.Duration) PeerRecord {
	publicKey, privateKey, id := testIdentity(t)
	return signedRecordForIdentity(t, now, offset, publicKey, privateKey, id)
}

func TestSignedPresenceRecordRoundTrip(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	record := signedRecord(t, now, 0)
	raw, err := EncodePeerRecord(record)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := ParsePeerRecord(raw, now)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.PublicID != record.PublicID {
		t.Fatal("public ID mismatch")
	}
}

func TestPresenceRecordRejectsTamperingAndExpiry(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	record := signedRecord(t, now, 0)
	record.Capabilities = []string{"webrtc-data-v1"}
	if err := record.Validate(now); err == nil {
		t.Fatal("tampered record accepted")
	}

	expired := signedRecord(t, now, -RecordTTL-time.Minute)
	if err := expired.Validate(now); err == nil {
		t.Fatal("expired record accepted")
	}
}

func TestDHTKeyAndValidatorBindIdentity(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	record := signedRecord(t, now, 0)
	raw, err := EncodePeerRecord(record)
	if err != nil {
		t.Fatal(err)
	}
	key, err := KeyForPublicID(record.PublicID)
	if err != nil {
		t.Fatal(err)
	}
	if err := (Validator{}).Validate(key, raw); err != nil {
		t.Fatal(err)
	}
	if err := (Validator{}).Validate(RecordKey("rly1wrong"), raw); err == nil {
		t.Fatal("wrong DHT key accepted")
	}
}

func TestValidatorSelectsNewestSignedRecord(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	publicKey, privateKey, id := testIdentity(t)
	older := signedRecordForIdentity(t, now, -time.Minute, publicKey, privateKey, id)
	newer := signedRecordForIdentity(t, now, -time.Second, publicKey, privateKey, id)
	oldBytes, err := EncodePeerRecord(older)
	if err != nil { t.Fatal(err) }
	newBytes, err := EncodePeerRecord(newer)
	if err != nil { t.Fatal(err) }
	key, err := KeyForPublicID(id)
	if err != nil { t.Fatal(err) }
	index, err := (Validator{}).Select(key, [][]byte{oldBytes, newBytes})
	if err != nil {
		t.Fatal(err)
	}
	if index != 1 {
		t.Fatalf("selected %d, want newest index 1", index)
	}
}

func TestTurnCredentialsAreTimeLimitedAndIdentityBound(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	_, privateKey, id := testIdentity(t)
	nonce := "0123456789abcdef0123456789abcdef"
	timestamp := now.Unix()
	signature := base64.RawURLEncoding.EncodeToString(ed25519.Sign(privateKey, TurnChallengeBytes(id, timestamp, nonce)))
	challenge := TurnChallenge{PublicID: id, Timestamp: timestamp, Nonce: nonce, Signature: signature}
	if err := VerifyTurnChallenge(challenge, now); err != nil {
		t.Fatal(err)
	}
	issued, err := IssueTurnCredentials("0123456789abcdef0123456789abcdef", "turn.example", []string{"turn:turn.example:3478"}, id, now, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if issued.TTLSeconds != 300 || issued.Username == "" || issued.Credential == "" {
		t.Fatal("incomplete TURN credentials")
	}
	if err := VerifyTurnChallenge(challenge, now.Add(2*time.Minute)); err == nil {
		t.Fatal("stale challenge accepted")
	}
}
