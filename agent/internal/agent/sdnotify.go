package agent

import (
	"context"
	"net"
	"os"
	"strconv"
	"time"
)

// sdNotify sends one state message to systemd's notify socket. A missing
// socket (running outside systemd, Type != notify) is a silent no-op.
func sdNotify(state string) {
	socket := os.Getenv("NOTIFY_SOCKET")
	if socket == "" {
		return
	}
	conn, err := net.DialUnix("unixgram", nil, &net.UnixAddr{Name: socket, Net: "unixgram"})
	if err != nil {
		return
	}
	defer conn.Close()
	_, _ = conn.Write([]byte(state))
}

// startWatchdog pings systemd at half the WatchdogSec interval so a hung
// agent gets restarted. No-op unless systemd passed WATCHDOG_USEC.
func startWatchdog(ctx context.Context) {
	usec, err := strconv.ParseInt(os.Getenv("WATCHDOG_USEC"), 10, 64)
	if err != nil || usec <= 0 {
		return
	}
	interval := time.Duration(usec) * time.Microsecond / 2
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				sdNotify("WATCHDOG=1")
			}
		}
	}()
}
