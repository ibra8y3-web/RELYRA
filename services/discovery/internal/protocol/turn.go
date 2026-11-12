package protocol

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const turnDomain = "RELYRA-TURN-CREDENTIALS-V1"

type TurnChallenge struct {
	PublicID  string `json:"publicId"`
	Timestamp int64  `json:"timestamp"`
	Nonce     string `json:"nonce"`
	Signature string `json:"signature"`
}


func ValidateTurnURL(raw string) error {
	if len(raw) == 0 || len(raw) > 512 || strings.ContainsAny(raw, " \t\r\n") {
		return errors.New("invalid TURN URL length or whitespace")
	}
	separator := strings.IndexByte(raw, ':')
	if separator <= 0 {
		return errors.New("TURN URL must specify turn or turns scheme")
	}
	scheme := strings.ToLower(raw[:separator])
	if scheme != "turn" && scheme != "turns" {
		return errors.New("TURN URL scheme must be turn or turns")
	}
	parsed, err := url.Parse("//" + raw[separator+1:])
	if err != nil || parsed.Hostname() == "" || parsed.User != nil ||
		parsed.Path != "" || parsed.Fragment != "" {
		return errors.New("invalid TURN server authority")
	}
	if parsed.Port() != "" {
		port, err := strconv.Atoi(parsed.Port())
		if err != nil || port < 1 || port > 65535 {
			return errors.New("invalid TURN server port")
		}
	}
	query, err := url.ParseQuery(parsed.RawQuery)
	if err != nil {
		return errors.New("invalid TURN transport query")
	}
	if len(query) > 0 {
		values := query["transport"]
		if len(query) != 1 || len(values) != 1 ||
			(values[0] != "udp" && values[0] != "tcp") {
			return errors.New("TURN transport must be udp or tcp")
		}
	}
	return nil
}

type TurnCredentials struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
	ExpiresAt  int64    `json:"expiresAt"`
	TTLSeconds int64    `json:"ttlSeconds"`
}

func TurnChallengeBytes(publicID string, timestamp int64, nonce string) []byte {
	return []byte(strings.Join([]string{
		turnDomain,
		strings.ToLower(strings.TrimSpace(publicID)),
		strconv.FormatInt(timestamp, 10),
		nonce,
	}, "\n"))
}

func VerifyTurnChallenge(challenge TurnChallenge, now time.Time) error {
	publicKey, err := DecodePublicID(challenge.PublicID)
	if err != nil {
		return err
	}
	if challenge.Timestamp <= 0 || abs64(now.Unix()-challenge.Timestamp) > 60 {
		return errors.New("TURN challenge timestamp is stale")
	}
	if len(challenge.Nonce) < 16 || len(challenge.Nonce) > 96 {
		return errors.New("invalid TURN challenge nonce")
	}
	signature, err := base64.RawURLEncoding.DecodeString(challenge.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize ||
		!ed25519.Verify(publicKey, TurnChallengeBytes(challenge.PublicID, challenge.Timestamp, challenge.Nonce), signature) {
		return errors.New("invalid TURN challenge signature")
	}
	return nil
}

func IssueTurnCredentials(sharedSecret, realm string, urls []string, publicID string, now time.Time, ttl time.Duration) (TurnCredentials, error) {
	if len(sharedSecret) < 32 || strings.TrimSpace(realm) == "" || len(urls) == 0 {
		return TurnCredentials{}, errors.New("TURN service is not configured")
	}
	if ttl < time.Minute || ttl > 10*time.Minute {
		return TurnCredentials{}, errors.New("invalid TURN credential lifetime")
	}
	if _, err := DecodePublicID(publicID); err != nil {
		return TurnCredentials{}, err
	}
	for _, url := range urls {
		if err := ValidateTurnURL(url); err != nil {
			return TurnCredentials{}, errors.New("invalid TURN URL configuration: " + err.Error())
		}
	}
	expires := now.Add(ttl).Unix()
	username := fmt.Sprintf("%d:%s", expires, strings.ToLower(publicID))
	mac := hmac.New(sha1.New, []byte(sharedSecret))
	_, _ = mac.Write([]byte(username))
	return TurnCredentials{
		URLs: append([]string(nil), urls...),
		Username: username,
		Credential: base64.StdEncoding.EncodeToString(mac.Sum(nil)),
		ExpiresAt: expires,
		TTLSeconds: int64(ttl.Seconds()),
	}, nil
}

func abs64(value int64) int64 {
	if value < 0 {
		return -value
	}
	return value
}
