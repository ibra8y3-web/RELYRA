package dhtvalidation

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"

	datastore "github.com/ipfs/go-datastore"

	"github.com/ibra8y3-web/RELYRA/services/discovery/internal/dhtdirectory"
	"github.com/ibra8y3-web/RELYRA/services/discovery/internal/protocol"
)

type Validator struct {
	presence protocol.Validator
	store datastore.Datastore
	mu *sync.Mutex
}

func New(store datastore.Datastore) Validator {
	return Validator{presence: protocol.NewValidator(store), store: store, mu: &sync.Mutex{}}
}

func normalizedKey(key string) string {
	key = strings.TrimPrefix(key, "/")
	return strings.TrimPrefix(key, "rlyra/")
}

func (v Validator) Validate(key string, value []byte) error {
	key = normalizedKey(key)
	if strings.HasPrefix(key, "peer/v1/") {
		return v.presence.Validate(key, value)
	}
	if !strings.HasPrefix(key, "community-directory/v1/") {
		return errors.New("unsupported RELYRA DHT record namespace")
	}
	record, err := dhtdirectory.Validate(key, value)
	if err != nil { return err }
	return v.acceptDirectoryFresh(record)
}

func (v Validator) Select(key string, values [][]byte) (int, error) {
	normalized := normalizedKey(key)
	if strings.HasPrefix(normalized, "peer/v1/") {
		return v.presence.Select(normalized, values)
	}
	best := -1
	var newest int64
	var bestBytes []byte
	for index, value := range values {
		if err := v.Validate(normalized, value); err != nil { continue }
		record, err := dhtdirectory.Validate(normalized, value)
		if err != nil { continue }
		if best < 0 || record.IssuedAt > newest ||
			(record.IssuedAt == newest && bytes.Compare(value, bestBytes) > 0) {
			best, newest, bestBytes = index, record.IssuedAt, value
		}
	}
	if best < 0 { return 0, errors.New("no valid RELYRA community directory record") }
	return best, nil
}

func (v Validator) acceptDirectoryFresh(record dhtdirectory.Record) error {
	if v.store == nil { return nil }
	if v.mu == nil { return errors.New("directory validator has no mutex") }
	v.mu.Lock()
	defer v.mu.Unlock()
	key := datastore.NewKey("/relyra/community-directory/highwater/" + record.PeerID)
	previous, err := v.store.Get(context.Background(), key)
	if err == nil {
		parts := strings.SplitN(string(previous), "\n", 2)
		if len(parts) != 2 { return errors.New("stored directory high-water mark is corrupt") }
		last, parseErr := strconv.ParseInt(parts[0], 10, 64)
		if parseErr != nil || last <= 0 { return errors.New("stored directory high-water mark is corrupt") }
		if record.IssuedAt < last { return errors.New("stale community directory replay rejected") }
		if record.IssuedAt == last {
			if record.Signature != parts[1] { return errors.New("conflicting directory at the same issue time") }
			return nil
		}
	} else if !errors.Is(err, datastore.ErrNotFound) {
		return fmt.Errorf("read directory high-water mark: %w", err)
	}
	if err := v.store.Put(context.Background(), key, []byte(strconv.FormatInt(record.IssuedAt, 10)+"\n"+record.Signature)); err != nil {
		return fmt.Errorf("persist directory high-water mark: %w", err)
	}
	return nil
}
