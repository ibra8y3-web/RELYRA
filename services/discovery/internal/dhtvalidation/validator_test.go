package dhtvalidation

import (
	"crypto/rand"
	"testing"
	"time"

	datastore "github.com/ipfs/go-datastore"
	libp2pcrypto "github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"

	"github.com/ibra8y3-web/RELYRA/services/discovery/internal/dhtdirectory"
)

func TestDirectoryValidatorPersistsHighWaterAcrossRestart(t *testing.T) {
	key, _, err := libp2pcrypto.GenerateEd25519Key(rand.Reader)
	if err != nil { t.Fatal(err) }
	peerID, err := peer.IDFromPrivateKey(key)
	if err != nil { t.Fatal(err) }
	now := time.Now().UTC().Truncate(time.Second)
	newer, err := dhtdirectory.Encode(peerID, key, nil, now.Add(-5*time.Second))
	if err != nil { t.Fatal(err) }
	older, err := dhtdirectory.Encode(peerID, key, nil, now.Add(-time.Minute))
	if err != nil { t.Fatal(err) }
	keyPath, err := dhtdirectory.KeyForPeerID(peerID.String())
	if err != nil { t.Fatal(err) }
	store := datastore.NewMapDatastore()
	first := New(store)
	if err := first.Validate(keyPath, newer); err != nil { t.Fatalf("newer snapshot rejected: %v", err) }
	restarted := New(store)
	if err := restarted.Validate(keyPath, older); err == nil { t.Fatal("older snapshot accepted after restart") }
	if err := restarted.Validate(keyPath, newer); err != nil { t.Fatalf("same snapshot should be idempotent: %v", err) }
}
