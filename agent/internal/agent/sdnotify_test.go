package agent

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func notifyListener(t *testing.T) (*net.UnixConn, string) {
	t.Helper()
	// Short directory: unix socket paths are capped (~104 bytes on macOS),
	// and t.TempDir() embeds the full test name.
	dir, err := os.MkdirTemp("", "sd")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	path := filepath.Join(dir, "n.sock")
	conn, err := net.ListenUnixgram("unixgram", &net.UnixAddr{Name: path, Net: "unixgram"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn, path
}

func readDatagram(t *testing.T, conn *net.UnixConn) string {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 64)
	n, _, err := conn.ReadFrom(buf)
	if err != nil {
		t.Fatalf("no notify datagram: %v", err)
	}
	return string(buf[:n])
}

func TestSdNotifySendsState(t *testing.T) {
	conn, path := notifyListener(t)
	t.Setenv("NOTIFY_SOCKET", path)

	sdNotify("READY=1")

	if got := readDatagram(t, conn); got != "READY=1" {
		t.Fatalf("got %q, want READY=1", got)
	}
}

func TestSdNotifyWithoutSocketIsNoop(t *testing.T) {
	t.Setenv("NOTIFY_SOCKET", "")
	sdNotify("READY=1") // must not panic or block
}

func TestWatchdogPingsAtHalfInterval(t *testing.T) {
	conn, path := notifyListener(t)
	t.Setenv("NOTIFY_SOCKET", path)
	t.Setenv("WATCHDOG_USEC", "100000") // 100 ms → ping every 50 ms

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	startWatchdog(ctx)

	if got := readDatagram(t, conn); got != "WATCHDOG=1" {
		t.Fatalf("got %q, want WATCHDOG=1", got)
	}
}
