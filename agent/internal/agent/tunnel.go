package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"time"

	"github.com/coder/websocket"
	"github.com/creack/pty"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

type tunnelPayload struct {
	SessionID  string `json:"sessionId"`
	VncPort    int    `json:"vncPort"`
	MaxSeconds int    `json:"maxSeconds"`
	// Shell serves an interactive login shell over the tunnel instead of
	// piping the local VNC server.
	Shell bool `json:"shell"`
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

	if payload.Shell {
		return runShell(ctx, client, payload.SessionID, reporter)
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

// runShell serves one shell session: it spawns an interactive login shell
// under a PTY and pipes it into the reverse tunnel, so the browser terminal
// drives a real TTY. The window is fixed (the tunnel is a raw byte relay);
// the shell runs as root, like the agent — every session is server-audited.
func runShell(ctx context.Context, client *api.Client, sessionID string, reporter *eventReporter) int {
	ws, err := client.DialTunnel(ctx, sessionID)
	if err != nil {
		reporter.log("cannot open tunnel: " + err.Error() + "\n")
		return 1
	}
	defer ws.Close(websocket.StatusNormalClosure, "")

	shell := os.Getenv("SHELL")
	if shell == "" {
		shell = "/bin/bash"
		if _, err := os.Stat(shell); err != nil {
			shell = "/bin/sh"
		}
	}
	cmd := exec.CommandContext(ctx, shell, "-i")
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: 40, Cols: 120})
	if err != nil {
		reporter.log("cannot start shell: " + err.Error() + "\n")
		return 1
	}
	defer func() {
		_ = ptmx.Close()
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}()

	reporter.log("shell session open (" + shell + ")\n")
	err = pipe(ctx, ws, ptmx)
	if isExpectedClose(ctx, err) {
		reporter.log("shell session closed\n")
		return 0
	}
	reporter.log("shell session closed: " + err.Error() + "\n")
	return 1
}

// pipe shovels bytes in both directions and returns the first error — which
// for an orderly shutdown is the peer's close. Closing ws and vnc (done by
// runTunnel's defers) unblocks the surviving goroutine; the channel is
// buffered so it never leaks.
func pipe(ctx context.Context, ws *websocket.Conn, local io.ReadWriter) error {
	errc := make(chan error, 2)
	go func() { // server → local (viewer/terminal input)
		for {
			kind, data, err := ws.Read(ctx)
			if err != nil {
				errc <- err
				return
			}
			if kind != websocket.MessageBinary {
				continue
			}
			if _, err := local.Write(data); err != nil {
				errc <- err
				return
			}
		}
	}()
	go func() { // local → server (framebuffer / terminal output)
		buf := make([]byte, 32*1024)
		for {
			n, err := local.Read(buf)
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
