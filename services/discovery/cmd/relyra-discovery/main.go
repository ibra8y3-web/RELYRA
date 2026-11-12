package main

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/ipfs/go-ds-pebble"
	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	libp2pprotocol "github.com/libp2p/go-libp2p/core/protocol"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/routing"
	ma "github.com/multiformats/go-multiaddr"

	presence "github.com/ibra8y3-web/RELYRA/services/discovery/internal/protocol"
	communities "github.com/ibra8y3-web/RELYRA/services/discovery/internal/community"
)

const (
	apiRecordLimit = 8 * 1024
	maxConcurrent  = 64
	recordRate     = 120
	turnRate       = 6
	communityReadRate = 60
	communityWriteRate = 30
	communityReportRate = 8
)

type fixedWindow struct {
	start time.Time
	count int
}

type limiter struct {
	mu      sync.Mutex
	entries map[string]fixedWindow
}

func newLimiter() *limiter { return &limiter{entries: make(map[string]fixedWindow)} }

func (l *limiter) allow(key string, limit int, window time.Duration) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	entry := l.entries[key]
	if entry.start.IsZero() || now.Sub(entry.start) >= window {
		entry = fixedWindow{start: now}
	}
	entry.count++
	l.entries[key] = entry
	if len(l.entries) > 10000 {
		for candidate, item := range l.entries {
			if now.Sub(item.start) > 2*window {
				delete(l.entries, candidate)
			}
		}
	}
	return entry.count <= limit
}

type server struct {
	dht          *dht.IpfsDHT
	host         host.Host
	datastore    *pebbleds.Datastore
	communities  *communities.Store
	turnSecret   string
	turnRealm    string
	turnURLs     []string
	turnTTL      time.Duration
	limiter      *limiter
	nonceMu      sync.Mutex
	usedNonces   map[string]time.Time
	semaphore    chan struct{}
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	node, err := newServer(ctx)
	if err != nil {
		log.Fatalf("start discovery node: %v", err)
	}
	defer node.close()

	listen := env("RELYRA_HTTP_LISTEN", "0.0.0.0:8080")
	httpServer := &http.Server{
		Addr:              listen,
		Handler:           node.routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 * 1024,
	}
	go func() {
		log.Printf("RELYRA discovery API listening on %s; libp2p peer %s", listen, node.host.ID())
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("HTTP server stopped: %v", err)
			stop()
		}
	}()

	<-ctx.Done()
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = httpServer.Shutdown(shutdown)
}

func newServer(ctx context.Context) (*server, error) {
	dataDir := env("RELYRA_DATA_DIR", "/var/lib/relyra-discovery")
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	privateKey, err := loadOrCreateNodeKey(filepath.Join(dataDir, "libp2p.key"))
	if err != nil {
		return nil, err
	}

	listenAddrs := splitList(env("RELYRA_LIBP2P_LISTEN", "/ip4/0.0.0.0/tcp/4001"))
	opts := []libp2p.Option{
		libp2p.Identity(privateKey),
		libp2p.ListenAddrStrings(listenAddrs...),
		libp2p.NATPortMap(),
	}
	if announced := splitList(os.Getenv("RELYRA_ANNOUNCE_MULTIADDRS")); len(announced) > 0 {
		external := make([]ma.Multiaddr, 0, len(announced))
		for _, raw := range announced {
			addr, parseErr := ma.NewMultiaddr(raw)
			if parseErr != nil {
				return nil, fmt.Errorf("invalid announce multiaddress %q: %w", raw, parseErr)
			}
			external = append(external, addr)
		}
		opts = append(opts, libp2p.AddrsFactory(func(in []ma.Multiaddr) []ma.Multiaddr {
			out := append([]ma.Multiaddr(nil), in...)
			return append(out, external...)
		}))
	}

	p2pHost, err := libp2p.New(opts...)
	if err != nil {
		return nil, err
	}

	datastore, err := pebbleds.NewDatastore(filepath.Join(dataDir, "dht"))
	if err != nil {
		_ = p2pHost.Close()
		return nil, err
	}

	bootstrap, err := parseBootstrapPeers(splitList(os.Getenv("RELYRA_BOOTSTRAP_PEERS")))
	if err != nil {
		_ = datastore.Close()
		_ = p2pHost.Close()
		return nil, err
	}
	router, err := dht.New(
		p2pHost,
		dht.Mode(dht.ModeServer),
		dht.ProtocolPrefix(libp2pprotocol.ID("/relyra")),
		dht.NamespacedValidator("rlyra", presence.Validator{}),
		dht.Datastore(datastore),
		dht.MaxRecordAge(5*time.Minute),
		dht.ValueGCInterval(time.Minute),
		dht.RoutingTablePersistence(time.Hour),
		dht.BootstrapPeers(bootstrap...),
		dht.QueryFilter(dht.PublicQueryFilter),
		dht.RoutingTableFilter(dht.PublicRoutingTableFilter),
		dht.RoutingTablePeerDiversityFilter(dht.NewRTPeerDiversityFilter(p2pHost, 2, 10)),
	)
	if err != nil {
		_ = datastore.Close()
		_ = p2pHost.Close()
		return nil, err
	}

	for _, peerInfo := range bootstrap {
		dialCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		if err := p2pHost.Connect(dialCtx, peerInfo); err != nil {
			log.Printf("bootstrap peer %s not reachable yet: %v", peerInfo.ID, err)
		}
		cancel()
	}
	bootCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	if err := router.Bootstrap(bootCtx); err != nil {
		log.Printf("DHT bootstrap is not complete yet: %v", err)
	}
	cancel()

	turnURLs := splitList(os.Getenv("RELYRA_TURN_URLS"))
	for _, url := range turnURLs {
		if err := presence.ValidateTurnURL(url); err != nil {
			_ = router.Close()
			_ = p2pHost.Close()
			_ = datastore.Close()
			return nil, fmt.Errorf("invalid RELYRA_TURN_URLS entry %q: %w", url, err)
		}
	}
	turnTTL := 5 * time.Minute
	if value := os.Getenv("RELYRA_TURN_TTL_SECONDS"); value != "" {
		seconds, parseErr := strconv.Atoi(value)
		if parseErr != nil || seconds < 60 || seconds > 600 {
			_ = router.Close()
			_ = p2pHost.Close()
			_ = datastore.Close()
			return nil, errors.New("RELYRA_TURN_TTL_SECONDS must be between 60 and 600")
		}
		turnTTL = time.Duration(seconds) * time.Second
	}

	return &server{
		dht: router, host: p2pHost, datastore: datastore,
		communities: communities.NewStore(datastore),
		turnSecret: os.Getenv("RELYRA_TURN_SHARED_SECRET"),
		turnRealm: env("RELYRA_TURN_REALM", "turn.relyra.invalid"),
		turnURLs: turnURLs, turnTTL: turnTTL,
		limiter: newLimiter(), usedNonces: make(map[string]time.Time),
		semaphore: make(chan struct{}, maxConcurrent),
	}, nil
}

func (s *server) close() {
	// Stop DHT handlers before closing the host and persistent datastore.
	_ = s.dht.Close()
	_ = s.host.Close()
	_ = s.datastore.Close()
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("POST /v1/records", s.publishRecord)
	mux.HandleFunc("GET /v1/records/{publicID}", s.lookupRecord)
	mux.HandleFunc("POST /v1/turn-credentials", s.turnCredentials)
	mux.HandleFunc("GET /v1/communities", s.discoverCommunities)
	mux.HandleFunc("POST /v1/communities", s.publishCommunity)
	mux.HandleFunc("GET /v1/communities/{communityID}", s.getCommunity)
	mux.HandleFunc("POST /v1/communities/{communityID}/events", s.publishCommunityEvent)
	mux.HandleFunc("GET /v1/communities/{communityID}/events", s.listCommunityEvents)
	mux.HandleFunc("POST /v1/communities/{communityID}/reports", s.submitCommunityReport)
	return s.guard(mux)
}

func (s *server) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case s.semaphore <- struct{}{}:
			defer func() { <-s.semaphore }()
		default:
			writeError(w, http.StatusServiceUnavailable, "busy")
			return
		}
		if r.Method != http.MethodGet {
			mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
			if err != nil || mediaType != "application/json" {
				writeError(w, http.StatusUnsupportedMediaType, "application/json required")
				return
			}
		}
		ip := clientIP(r)
		limit := recordRate
		switch {
		case strings.Contains(r.URL.Path, "/turn-credentials"):
			limit = turnRate
		case strings.Contains(r.URL.Path, "/reports"):
			limit = communityReportRate
		case strings.Contains(r.URL.Path, "/events") || r.Method == http.MethodPost && strings.HasPrefix(r.URL.Path, "/v1/communities"):
			limit = communityWriteRate
		case strings.HasPrefix(r.URL.Path, "/v1/communities"):
			limit = communityReadRate
		}
		if !s.limiter.allow(ip, limit, time.Minute) {
			writeError(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"service": "relyra-discovery",
		"status": "ok",
		"peerId": s.host.ID().String(),
		"protocol": "/relyra/kad/1.0.0",
		"bootstrapPeersConfigured": s.dht.RoutingTable().Size() > 0,
	})
}

func (s *server) publishRecord(w http.ResponseWriter, r *http.Request) {
	if s.dht.RoutingTable().Size() == 0 {
		writeError(w, http.StatusServiceUnavailable, "DHT routing table is empty; configure reachable bootstrap peers")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, apiRecordLimit)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid or oversized record")
		return
	}
	record, err := presence.ParsePeerRecord(raw, time.Now())
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	key, err := presence.KeyForPublicID(record.PublicID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid RELYRA identity")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	if err := s.dht.PutValue(ctx, key, raw); err != nil {
		writeError(w, http.StatusServiceUnavailable, "DHT publish failed")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusAccepted, map[string]any{
		"accepted": true,
		"publicId": record.PublicID,
		"expiresAt": record.ExpiresAt,
	})
}

func (s *server) lookupRecord(w http.ResponseWriter, r *http.Request) {
	if s.dht.RoutingTable().Size() == 0 {
		writeError(w, http.StatusServiceUnavailable, "DHT routing table is empty; configure reachable bootstrap peers")
		return
	}
	publicID := strings.ToLower(strings.TrimSpace(r.PathValue("publicID")))
	key, err := presence.KeyForPublicID(publicID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid RELYRA public ID")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	value, err := s.dht.GetValue(ctx, key)
	if err != nil {
		if errors.Is(err, routing.ErrNotFound) {
			writeError(w, http.StatusNotFound, "peer presence not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "DHT lookup failed")
		}
		return
	}
	if _, err := presence.ParsePeerRecord(value, time.Now()); err != nil {
		writeError(w, http.StatusNotFound, "peer presence expired")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(value)
}

func (s *server) discoverCommunities(w http.ResponseWriter, r *http.Request) {
	limit := 20
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > communities.MaxDiscoverPage {
			writeError(w, http.StatusBadRequest, "invalid discovery limit")
			return
		}
		limit = value
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	result, err := s.communities.Discover(ctx, limit)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "community discovery unavailable")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"communities": result})
}

func (s *server) publishCommunity(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, communities.MaxManifestBytes)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid or oversized community manifest")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	manifest, err := s.communities.PublishManifest(ctx, raw)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusAccepted, map[string]any{"accepted": true, "community": manifest})
}

func (s *server) getCommunity(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	manifest, err := s.communities.GetManifest(ctx, r.PathValue("communityID"), false)
	if err != nil {
		writeError(w, http.StatusNotFound, "public community not found")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, manifest)
}

func (s *server) publishCommunityEvent(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, communities.MaxEventBytes)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid or oversized moderation event")
		return
	}
	var event communities.ModerationEvent
	if err := json.Unmarshal(raw, &event); err != nil || !strings.EqualFold(event.CommunityID, r.PathValue("communityID")) {
		writeError(w, http.StatusBadRequest, "community ID does not match event")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	accepted, err := s.communities.AppendModerationEvent(ctx, raw)
	if err != nil {
		writeError(w, http.StatusForbidden, err.Error())
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusAccepted, map[string]any{"accepted": true, "event": accepted})
}

func (s *server) listCommunityEvents(w http.ResponseWriter, r *http.Request) {
	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > communities.MaxEventsPage {
			writeError(w, http.StatusBadRequest, "invalid event page size")
			return
		}
		limit = value
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	result, err := s.communities.Events(ctx, r.PathValue("communityID"), limit)
	if err != nil {
		writeError(w, http.StatusNotFound, "public community events not found")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"events": result})
}

func (s *server) submitCommunityReport(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, communities.MaxReportBytes)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid or oversized community report")
		return
	}
	var report communities.Report
	if err := json.Unmarshal(raw, &report); err != nil || !strings.EqualFold(report.CommunityID, r.PathValue("communityID")) {
		writeError(w, http.StatusBadRequest, "community ID does not match report")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	accepted, err := s.communities.SubmitReport(ctx, raw)
	if err != nil {
		writeError(w, http.StatusForbidden, err.Error())
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusAccepted, map[string]any{"accepted": true, "reportId": accepted.ReportID})
}

func (s *server) turnCredentials(w http.ResponseWriter, r *http.Request) {
	if s.turnSecret == "" || len(s.turnURLs) == 0 {
		writeError(w, http.StatusServiceUnavailable, "TURN credentials are not configured")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2048)
	var challenge presence.TurnChallenge
	if err := json.NewDecoder(r.Body).Decode(&challenge); err != nil {
		writeError(w, http.StatusBadRequest, "invalid TURN challenge")
		return
	}
	if err := presence.VerifyTurnChallenge(challenge, time.Now()); err != nil {
		writeError(w, http.StatusUnauthorized, "invalid identity challenge")
		return
	}
	nonceKey := strings.ToLower(challenge.PublicID) + ":" + challenge.Nonce
	if !s.useNonce(nonceKey) {
		writeError(w, http.StatusConflict, "TURN challenge already used")
		return
	}
	credentials, err := presence.IssueTurnCredentials(
		s.turnSecret, s.turnRealm, s.turnURLs, challenge.PublicID, time.Now(), s.turnTTL,
	)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "TURN credentials unavailable")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, credentials)
}

func (s *server) useNonce(key string) bool {
	now := time.Now()
	s.nonceMu.Lock()
	defer s.nonceMu.Unlock()
	for nonce, expiry := range s.usedNonces {
		if !expiry.After(now) {
			delete(s.usedNonces, nonce)
		}
	}
	if _, exists := s.usedNonces[key]; exists {
		return false
	}
	s.usedNonces[key] = now.Add(2 * time.Minute)
	return true
}

func loadOrCreateNodeKey(path string) (crypto.PrivKey, error) {
	if raw, err := os.ReadFile(path); err == nil {
		key, decodeErr := crypto.UnmarshalPrivateKey(raw)
		if decodeErr != nil {
			return nil, fmt.Errorf("decode libp2p identity: %w", decodeErr)
		}
		return key, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	key, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		return nil, err
	}
	raw, err := crypto.MarshalPrivateKey(key)
	if err != nil {
		return nil, err
	}
	temp := path + ".tmp"
	if err := os.WriteFile(temp, raw, 0o600); err != nil {
		return nil, err
	}
	if err := os.Rename(temp, path); err != nil {
		_ = os.Remove(temp)
		return nil, err
	}
	return key, nil
}

func parseBootstrapPeers(raw []string) ([]peer.AddrInfo, error) {
	peers := make([]peer.AddrInfo, 0, len(raw))
	seen := make(map[peer.ID]struct{})
	for _, value := range raw {
		address, err := ma.NewMultiaddr(value)
		if err != nil {
			return nil, fmt.Errorf("invalid bootstrap multiaddress %q: %w", value, err)
		}
		info, err := peer.AddrInfoFromP2pAddr(address)
		if err != nil || len(info.Addrs) == 0 {
			return nil, fmt.Errorf("bootstrap address must include /p2p/<peer-id>: %q", value)
		}
		if _, ok := seen[info.ID]; ok {
			continue
		}
		seen[info.ID] = struct{}{}
		peers = append(peers, *info)
	}
	return peers, nil
}

func splitList(value string) []string {
	parts := strings.FieldsFunc(value, func(r rune) bool { return r == ',' || r == ';' || r == '\n' })
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

func clientIP(r *http.Request) string {
	if strings.EqualFold(os.Getenv("RELYRA_TRUST_PROXY"), "true") {
		if forwarded := strings.TrimSpace(r.Header.Get("X-Real-IP")); forwarded != "" {
			if ip := net.ParseIP(forwarded); ip != nil {
				return ip.String()
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
