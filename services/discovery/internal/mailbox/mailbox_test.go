package mailbox

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strings"
	"testing"
	"time"

	datastore "github.com/ipfs/go-datastore"
)

func mailboxIdentity(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey, string) {
	t.Helper()
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil { t.Fatal(err) }
	sum := sha256.Sum256(publicKey)
	raw := append(append([]byte(nil), publicKey...), sum[0], sum[1])
	id := "rly1" + strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(raw))
	return publicKey, privateKey, id
}

func signedEnvelope(t *testing.T, senderPrivate ed25519.PrivateKey, senderID, recipientID string, now time.Time, payload []byte) []byte {
	t.Helper()
	digest := sha256.Sum256(payload)
	digestText := hex.EncodeToString(digest[:])
	envelope := Envelope{
		Version: Version,
		MessageID: digestText,
		SenderPublicID: senderID,
		RecipientPublicID: recipientID,
		IssuedAt: now.Unix(),
		ExpiresAt: now.Add(time.Hour).Unix(),
		Payload: base64.RawURLEncoding.EncodeToString(payload),
		Digest: digestText,
	}
	envelope.Signature = base64.RawURLEncoding.EncodeToString(ed25519.Sign(senderPrivate, envelope.signingBytes()))
	raw, err := json.Marshal(envelope)
	if err != nil { t.Fatal(err) }
	return raw
}

func TestMailboxStoresOnlyVerifiedOpaquePayloadAndAllowsRecipientAck(t *testing.T) {
	ctx := context.Background()
	_, senderPrivate, senderID := mailboxIdentity(t)
	_, _, recipientID := mailboxIdentity(t)
	store := NewStore(datastore.NewMapDatastore())
	now := time.Now().UTC().Truncate(time.Second)
	payload := []byte{0, 1, 2, 3, 250, 255}
	raw := signedEnvelope(t, senderPrivate, senderID, recipientID, now, payload)
	inserted, err := store.Put(ctx, raw)
	if err != nil || !inserted { t.Fatalf("first enqueue failed: inserted=%v err=%v", inserted, err) }
	inserted, err = store.Put(ctx, raw)
	if err != nil || inserted { t.Fatalf("duplicate enqueue was not idempotent: inserted=%v err=%v", inserted, err) }
	items, err := store.List(ctx, recipientID, 10)
	if err != nil || len(items) != 1 || items[0].MessageID == "" { t.Fatalf("recipient inbox mismatch: %v %v", items, err) }
	if err := store.Delete(ctx, senderID, items[0].MessageID); err == nil { t.Fatal("sender must not be able to acknowledge for recipient") }
	if err := store.Delete(ctx, recipientID, items[0].MessageID); err != nil { t.Fatalf("recipient acknowledgement failed: %v", err) }
	items, err = store.List(ctx, recipientID, 10)
	if err != nil || len(items) != 0 { t.Fatalf("acknowledged mailbox item remains: %v %v", items, err) }
}

func TestMailboxRejectsTamperingAndExpiredEnvelopes(t *testing.T) {
	_, senderPrivate, senderID := mailboxIdentity(t)
	_, _, recipientID := mailboxIdentity(t)
	now := time.Now().UTC().Truncate(time.Second)
	store := NewStore(datastore.NewMapDatastore())
	store.SetClockForTest(func() time.Time { return now })
	raw := signedEnvelope(t, senderPrivate, senderID, recipientID, now, []byte("ciphertext"))
	tampered := append([]byte(nil), raw...)
	for i := range tampered { if tampered[i] == 'c' { tampered[i] = 'd'; break } }
	if _, err := store.Put(context.Background(), tampered); err == nil { t.Fatal("tampered envelope accepted") }

	expired := signedEnvelope(t, senderPrivate, senderID, recipientID, now.Add(-2*MaxTTL), []byte("expired"))
	if _, err := store.Put(context.Background(), expired); err == nil { t.Fatal("expired envelope accepted") }
}
