package protocol

import (
	"encoding/json"
	"testing"
)

func TestTurnCredentialsUseAndroidJSONFieldNames(t *testing.T) {
	raw, err := json.Marshal(TurnCredentials{
		Username: "expiry:rly1id",
		Credential: "short-lived-secret",
		ExpiresAt: 1_800_000_300,
		TTLSeconds: 300,
		URLs: []string{"turn:turn.example:3478?transport=udp"},
	})
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"username", "credential", "expiresAt", "ttlSeconds", "urls"} {
		if _, ok := fields[key]; !ok {
			t.Fatalf("missing JSON field %q: %s", key, raw)
		}
	}
}
