package dhtdirectory

import (
	"crypto/rand"
	"testing"
	"time"

	libp2pcrypto "github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
)

func TestDirectoryRecordIsSignedPeerBoundAndShortLived(t *testing.T) {
	privateKey, _, err := libp2pcrypto.GenerateEd25519Key(rand.Reader)
	if err != nil { t.Fatal(err) }
	peerID, err := peer.IDFromPrivateKey(privateKey)
	if err != nil { t.Fatal(err) }
	now := time.Now().UTC().Truncate(time.Second)
	raw, err := Encode(peerID, privateKey, nil, now)
	if err != nil { t.Fatal(err) }
	parsed, err := Parse(raw, peerID.String(), now.Add(time.Second))
	if err != nil { t.Fatal(err) }
	if parsed.PeerID != peerID.String() || parsed.ExpiresAt-parsed.IssuedAt != int64(TTL.Seconds()) {
		t.Fatal("directory fields do not match expected publisher and lease")
	}
	if _, err := Parse(raw, "12D3KooWwrong", now); err == nil {
		t.Fatal("directory record accepted for another node")
	}
	if _, err := Parse(raw, peerID.String(), now.Add(TTL+time.Second)); err == nil {
		t.Fatal("expired directory record accepted")
	}
}

func TestDirectoryRecordRejectsTampering(t *testing.T) {
	privateKey, _, err := libp2pcrypto.GenerateEd25519Key(rand.Reader)
	if err != nil { t.Fatal(err) }
	peerID, err := peer.IDFromPrivateKey(privateKey)
	if err != nil { t.Fatal(err) }
	now := time.Now().UTC().Truncate(time.Second)
	raw, err := Encode(peerID, privateKey, nil, now)
	if err != nil { t.Fatal(err) }
	tampered := append([]byte(nil), raw...)
	for i := range tampered {
		if tampered[i] == '3' { tampered[i] = '4'; break }
	}
	if _, err := Parse(tampered, peerID.String(), now); err == nil {
		t.Fatal("tampered directory record accepted")
	}
}
