package protocol

import (
	"bytes"
	"errors"
	"strconv"
	"strings"
	"time"
)

type Validator struct{}

func (Validator) Validate(key string, value []byte) error {
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
	return nil
}

// Select keeps the newest valid signed presence record; ties are deterministic.
func (Validator) Select(key string, values [][]byte) (int, error) {
	best := -1
	var bestTime int64
	var bestBytes []byte
	for index, value := range values {
		if err := (Validator{}).Validate(key, value); err != nil {
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
