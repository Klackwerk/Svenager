package api

import (
	"encoding/json"
	"strings"
	"testing"
)

// The first chunk of a job has seq 0 — omitempty would drop the field and
// the server would lose that chunk. Guard the wire format.
func TestJobEventSerializesSeqZero(t *testing.T) {
	data, err := json.Marshal(JobEvent{Event: "log", Seq: 0, Chunk: "x"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"seq":0`) {
		t.Fatalf("seq 0 missing from wire format: %s", data)
	}
}
