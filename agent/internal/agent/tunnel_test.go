package agent

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

// fakeVNC listens on localhost, greets like a VNC server and captures what
// the tunnel delivers to it.
func fakeVNC(t *testing.T, greeting string) (int, chan []byte) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	received := make(chan []byte, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		conn.Write([]byte(greeting))
		buf := make([]byte, 64)
		n, err := conn.Read(buf)
		if err == nil {
			received <- buf[:n]
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, received
}

// tunnelServer fakes the broker: sends one binary frame to the agent, records
// the first frame it receives, then closes normally.
func tunnelServer(t *testing.T, wantToken string) (*httptest.Server, chan []byte) {
	t.Helper()
	fromAgent := make(chan []byte, 1)
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/agent/jobs/", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("{}"))
	})
	mux.HandleFunc("/api/v1/agent/tunnel/", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+wantToken {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		ctx := r.Context()
		if err := c.Write(ctx, websocket.MessageBinary, []byte("keystroke")); err != nil {
			return
		}
		_, data, err := c.Read(ctx)
		if err != nil {
			return
		}
		fromAgent <- data
		c.Close(websocket.StatusNormalClosure, "session ended")
	})
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	return ts, fromAgent
}

func newTunnelJob(t *testing.T, port int) *api.Job {
	t.Helper()
	payload, err := json.Marshal(map[string]any{"sessionId": "s1", "vncPort": port, "maxSeconds": 10})
	if err != nil {
		t.Fatal(err)
	}
	return &api.Job{ID: "1", Type: "OPEN_TUNNEL", Payload: payload}
}

func TestTunnelPipesBothWaysAndEndsCleanly(t *testing.T) {
	port, received := fakeVNC(t, "RFB 003.008\n")
	ts, fromAgent := tunnelServer(t, "tok")

	cfg := &config.Config{ServerURL: ts.URL, DeviceToken: "tok"}
	client := api.NewClient(cfg, "test")
	reporter := &eventReporter{ctx: context.Background(), client: client, jobID: "1"}

	if code := runTunnel(context.Background(), cfg, client, newTunnelJob(t, port), reporter); code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if got := string(<-fromAgent); got != "RFB 003.008\n" {
		t.Errorf("server received %q, want VNC greeting", got)
	}
	select {
	case got := <-received:
		if string(got) != "keystroke" {
			t.Errorf("VNC received %q, want \"keystroke\"", got)
		}
	case <-time.After(5 * time.Second):
		t.Error("VNC server never received the viewer frame")
	}
}

func TestTunnelFailsWithoutLocalVNC(t *testing.T) {
	ts, _ := tunnelServer(t, "tok")
	cfg := &config.Config{ServerURL: ts.URL, DeviceToken: "tok"}
	client := api.NewClient(cfg, "test")
	reporter := &eventReporter{ctx: context.Background(), client: client, jobID: "1"}

	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	closedPort := ln.Addr().(*net.TCPAddr).Port
	ln.Close()

	if code := runTunnel(context.Background(), cfg, client, newTunnelJob(t, closedPort), reporter); code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
}

func TestTunnelFailsOnRejectedHandshake(t *testing.T) {
	port, _ := fakeVNC(t, "RFB 003.008\n")
	ts, _ := tunnelServer(t, "other-token")
	cfg := &config.Config{ServerURL: ts.URL, DeviceToken: "tok"}
	client := api.NewClient(cfg, "test")
	reporter := &eventReporter{ctx: context.Background(), client: client, jobID: "1"}

	if code := runTunnel(context.Background(), cfg, client, newTunnelJob(t, port), reporter); code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
}

func TestRunShellPipesPTYOverTunnel(t *testing.T) {
	// Server end of the tunnel: accept the agent's websocket and speak raw
	// bytes to the PTY on the other side.
	srvWS := make(chan *websocket.Conn, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/events") {
			w.Write([]byte(`{"ok":true}`))
			return
		}
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		srvWS <- c
		<-r.Context().Done()
	}))
	defer server.Close()

	cfg := &config.Config{ServerURL: server.URL, DeviceToken: "svdt_test", StateDir: t.TempDir()}
	client := api.NewClient(cfg, "test")
	reporter := &eventReporter{ctx: context.Background(), client: client, jobID: "shell-1"}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	done := make(chan int, 1)
	go func() { done <- runShell(ctx, client, "sess-shell", reporter) }()

	ws := <-srvWS
	// The interactive shell echoes input and runs it; a unique marker proves
	// the PTY is live and bytes flow both ways.
	if err := ws.Write(ctx, websocket.MessageBinary, []byte("echo svenager-shell-ok\n")); err != nil {
		t.Fatalf("write to shell: %v", err)
	}
	deadline := time.Now().Add(10 * time.Second)
	var got []byte
	for time.Now().Before(deadline) {
		_, data, err := ws.Read(ctx)
		if err != nil {
			t.Fatalf("read from shell: %v", err)
		}
		got = append(got, data...)
		if strings.Contains(string(got), "svenager-shell-ok") {
			break
		}
	}
	if !strings.Contains(string(got), "svenager-shell-ok") {
		t.Fatalf("marker not seen in shell output: %q", got)
	}

	ws.Close(websocket.StatusNormalClosure, "")
	select {
	case code := <-done:
		if code != 0 {
			t.Errorf("runShell exit = %d, want 0", code)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("runShell did not return after tunnel close")
	}
}
