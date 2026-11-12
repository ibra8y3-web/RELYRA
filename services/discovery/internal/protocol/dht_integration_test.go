package protocol

import (
	"context"
	"testing"
	"time"

	datastore "github.com/ipfs/go-datastore"
	libp2p "github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	libp2pprotocol "github.com/libp2p/go-libp2p/core/protocol"
)

func TestKademliaPresenceReplicatesAcrossThreeNodes(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
	defer cancel()

	const nodeCount = 3
	hosts := make([]host.Host, 0, nodeCount)
	routers := make([]*dht.IpfsDHT, 0, nodeCount)
	for i := 0; i < nodeCount; i++ {
		store := datastore.NewMapDatastore()
		h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
		if err != nil { t.Fatalf("create libp2p host %d: %v", i, err) }
		d, err := dht.New(
			h,
			dht.Mode(dht.ModeServer),
			dht.ProtocolPrefix(libp2pprotocol.ID("/relyra")),
			dht.NamespacedValidator("rlyra", NewValidator(store)),
			dht.Datastore(store),
		)
		if err != nil {
				_ = h.Close()
				t.Fatalf("create Kademlia node %d: %v", i, err)
		}
		hosts = append(hosts, h)
		routers = append(routers, d)
	}
	defer func() {
		for _, router := range routers { _ = router.Close() }
		for _, h := range hosts { _ = h.Close() }
	}()

	// Full mesh keeps the test independent of public bootstrap infrastructure.
	for i := 0; i < nodeCount; i++ {
		for j := i + 1; j < nodeCount; j++ {
			info := peer.AddrInfo{ID: hosts[j].ID(), Addrs: hosts[j].Addrs()}
			if err := hosts[i].Connect(ctx, info); err != nil {
				t.Fatalf("connect node %d to %d: %v", i, j, err)
			}
		}
	}
	for _, router := range routers {
		if err := router.Bootstrap(ctx); err != nil {
			t.Fatalf("bootstrap local DHT: %v", err)
		}
	}

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		ready := true
		for _, router := range routers {
			if router.RoutingTable().Size() < nodeCount-1 { ready = false; break }
		}
		if ready { break }
		time.Sleep(100 * time.Millisecond)
	}
	for i, router := range routers {
		if router.RoutingTable().Size() < nodeCount-1 {
			t.Fatalf("node %d routing table did not learn both peers (size=%d)", i, router.RoutingTable().Size())
		}
	}

	publicKey, privateKey, id := testIdentity(t)
	record := signedRecordForIdentity(t, time.Now().UTC().Truncate(time.Second), 0, publicKey, privateKey, id)
	raw, err := EncodePeerRecord(record)
	if err != nil { t.Fatal(err) }
	key, err := KeyForPublicID(id)
	if err != nil { t.Fatal(err) }
	if err := routers[0].PutValue(ctx, key, raw); err != nil {
		t.Fatalf("publish signed presence through DHT: %v", err)
	}
	value, err := routers[2].GetValue(ctx, key)
	if err != nil { t.Fatalf("read signed presence from another DHT node: %v", err) }
	parsed, err := ParsePeerRecord(value, time.Now())
	if err != nil { t.Fatalf("remote DHT value failed signature/expiry verification: %v", err) }
	if parsed.PublicID != id { t.Fatalf("received identity %q, want %q", parsed.PublicID, id) }
}
