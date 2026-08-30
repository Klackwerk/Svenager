package agent

import (
	"testing"
	"time"
)

func TestNextDelayJitterStaysNearBase(t *testing.T) {
	base := 60 * time.Second
	for i := 0; i < 100; i++ {
		d := nextDelay(base, 0)
		if d < 54*time.Second || d > 66*time.Second {
			t.Fatalf("delay %v outside ±10%% of %v", d, base)
		}
	}
}

func TestNextDelayBacksOffExponentiallyAndCaps(t *testing.T) {
	base := 60 * time.Second
	cases := []struct {
		failures int
		min, max time.Duration
	}{
		{1, 108 * time.Second, 132 * time.Second},
		{2, 216 * time.Second, 264 * time.Second},
		{100, maxBackoff * 9 / 10, maxBackoff * 11 / 10},
	}
	for _, c := range cases {
		for i := 0; i < 20; i++ {
			d := nextDelay(base, c.failures)
			if d < c.min || d > c.max {
				t.Fatalf("failures=%d: delay %v outside [%v, %v]", c.failures, d, c.min, c.max)
			}
		}
	}
}

func TestCollectFactsHasCoreKeys(t *testing.T) {
	facts := collectFacts("")
	for _, key := range []string{"os", "arch", "hostname"} {
		if facts[key] == "" {
			t.Errorf("fact %q missing", key)
		}
	}
	if _, present := facts["ip"]; present {
		t.Errorf("no server URL must not yield a primary ip, got %q", facts["ip"])
	}
}

func TestPrimaryIPUsesLocalRoute(t *testing.T) {
	// Loopback is always routable, so the source address is loopback too.
	ip := primaryIP("http://127.0.0.1:9")
	if ip != "127.0.0.1" {
		t.Errorf("primaryIP = %q, want 127.0.0.1", ip)
	}
	if primaryIP("not a url") != "" || primaryIP("") != "" {
		t.Errorf("unparseable server URLs must yield no ip")
	}
}
