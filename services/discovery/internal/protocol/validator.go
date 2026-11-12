package protocol

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	datastore "github.com/ipfs/go-datastore"
)

// Validator remembers the newest signed lease seen for each RELYRA identity.
// The high-water mark shares the persistent DHT datastore, so a restart does
// not make an older (but not yet expired) presence record acceptable again.
type Validator struct {
	highWater datastore.Datastore
	mu        *sync.Mutex
}

// NewValidator creates a restart-safe validator. The zero value remains
// available for stateless validation in isolated protocol tests.
func NewValidator(store datastore.Datastore) Validator {
	return Validator{highWater: store, mu: &sync.Mutex{}}
}

func (v Validator) Validate(key string, value []byte) error {
	expectedID := ""
	switch {
	case strings.HasPrefix(key, peerKeyPrefix):
		expectedID = strings.TrimPrefix(key, peerKeyPrefix)
	case strings.HasPrefix(key, "peer/v1/"):
		// NamespacedValidator implementations may pass the namespace-stripped path.
		expectedID = strings.TrimPrefix(key, "peer/v1/")
	default:
		return errors.New("invalid RELYRA DHT key")
	}
	if expectedID == "" || strings.Contains(expectedID, "/") {
		return errors.New("invalid RELYRA DHT key")
	}
	record, err := ParsePeerRecord(value, time.Now())
	if err != nil {
		return err
	}
	if !strings.EqualFold(record.PublicID, expectedID) {
		return errors.New("record identity does not match DHT key")
	}
	return v.acceptFresh(record)
}

// Select keeps the newest valid signed presence record; ties are deterministic.
func (v Validator) Select(key string, values [][]byte) (int, error) {
	best := -1
	var bestTime int64
	var bestBytes []byte
	for index, value := range values {
		if err := v.Validate(key, value); err != nil {
			continue
		}
		record, err := ParsePeerRecord(value, time.Now())
		if err != nil {
			continue
		}
		if best == -1 || record.IssuedAt > bestTime ||
			(record.IssuedAt == bestTime && bytes.Compare(value, bestBytes) > 0) {
			best = index
			bestTime = record.IssuedAt
			bestBytes = value
		}
	}
	if best < 0 {
		return 0, errors.New("no valid RELYRA presence record")
	}
	return best, nil
}

func RecordKey(publicID string) string {
	return peerKeyPrefix + strings.ToLower(publicID)
}

func FormatPeerRecordAge(record PeerRecord) string {
	return strconv.FormatInt(record.ExpiresAt-record.IssuedAt, 10)
}


// acceptFresh persists the greatest signed issue time observed for this identity.
// Exact redelivery is idempotent; older leases and conflicting signatures at
// the same timestamp are rejected. Datastore errors fail closed.
func (v Validator) acceptFresh(record PeerRecord) error {
	if v.highWater == nil {
		return nil
	}
	if v.mu == nil {
		return errors.New("persistent presence validator has no mutex")
	}
	v.mu.Lock()
	defer v.mu.Unlock()

	key := datastore.NewKey("/relyra/presence/highwater/" + strings.ToLower(record.PublicID))
	previous, err := v.highWater.Get(context.Background(), key)
	if err == nil {
		parts := strings.SplitN(string(previous), "\n", 2)
		if len(parts) != 2 {
			return errors.New("stored presence high-water mark is corrupt")
		}
		lastIssuedAt, parseErr := strconv.ParseInt(parts[0], 10, 64)
		if parseErr != nil || lastIssuedAt <= 0 {
			return errors.New("stored presence high-water mark is corrupt")
		}
		if record.IssuedAt < lastIssuedAt {
			return errors.New("stale presence record replay rejected")
		}
		if record.IssuedAt == lastIssuedAt {
			if record.Signature != parts[1] {
				return errors.New("conflicting presence record at the same issue time")
			}
			return nil
		}
	} else if !errors.Is(err, datastore.ErrNotFound) {
		return fmt.Errorf("read presence high-water mark: %w", err)
	}

	value := []byte(strconv.FormatInt(record.IssuedAt, 10) + "\n" + record.Signature)
	if err := v.highWater.Put(context.Background(), key, value); err != nil {
		return fmt.Errorf("persist presence high-water mark: %w", err)
	}
	return nil
}
