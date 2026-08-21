package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/coder/websocket"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

type tunnelPayload struct {
	SessionID  string `json:"sessionId"`
	VncPort    int    `json:"vncPort"`
	MaxSeconds int    `json:"maxSeconds"`
}

// runTunnel serves one OPEN_TUNNEL job: it dials the server's reverse-tunnel
// endpoint and pipes it into the localhost-bound VNC server until either side
// closes or the session's time budget runs out.
func runTunnel(ctx context.Context, cfg *config.Config, client *api.Client, job *api.Job, reporter *eventReporter) int {
	var payload tunnelPayload
	if err := json.Unmarshal(job.Payload, &payload); err != nil {
		reporter.log("invalid job payload: " + err.Error() + "\n")
		return 1
	}
	if payload.VncPort == 0 {
		payload.VncPort = 5900
	}
	if payload.MaxSeconds > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(payload.MaxSeconds)*time.Second)
		defer cancel()
	}

	vncAddr := fmt.Sprintf("127.0.0.1:%d", payload.VncPort)
	vnc, err := net.Dial("tcp", vncAddr)
	if err != nil {
		reporter.log("cannot reach local VNC server " + vncAddr + ": " + err.Error() + "\n")
		return 1
	}
	defer vnc.Close()

	ws, err := client.DialTunnel(ctx, payload.SessionID)
	if err != nil {
		reporter.log("cannot open tunnel: " + err.Error() + "\n")
		return 1
	}
	defer ws.Close(websocket.StatusNormalClosure, "")

	reporter.log("tunnel open to " + vncAddr + "\n")
	err = pipe(ctx, ws, vnc)
	if isExpectedClose(ctx, err) {
		reporter.log("tunnel closed\n")
		return 0
	}
	reporter.log("tunnel closed: " + err.Error() + "\n")
	return 1
}

// pipe shovels bytes in both directions and returns the first error — which
// for an orderly shutdown is the peer's close. Closing ws and vnc (done by
// runTunnel's defers) unblocks the surviving goroutine; the channel is
// buffered so it never leaks.
func pipe(ctx context.Context, ws *websocket.Conn, vnc net.Conn) error {
	errc := make(chan error, 2)
	go func() { // server → VNC (viewer input)
		for {
			kind, data, err := ws.Read(ctx)
			if err != nil {
				errc <- err
				return
			}
			if kind != websocket.MessageBinary {
				continue
			}
			if _, err := vnc.Write(data); err != nil {
				errc <- err
				return
			}
		}
	}()
	go func() { // VNC → server (framebuffer)
		buf := make([]byte, 32*1024)
		for {
			n, err := vnc.Read(buf)
			if n > 0 {
				if werr := ws.Write(ctx, websocket.MessageBinary, buf[:n]); werr != nil {
					errc <- werr
					return
				}
			}
			if err != nil {
				errc <- err
				return
			}
		}
	}()
	return <-errc
}

// isExpectedClose: a normal WebSocket close, TCP EOF or the session's own
// time limit all mean "the session ended", not "the tunnel broke".
func isExpectedClose(ctx context.Context, err error) bool {
	switch {
	case err == nil, errors.Is(err, io.EOF), ctx.Err() != nil:
		return true
	default:
		return websocket.CloseStatus(err) == websocket.StatusNormalClosure
	}
}
