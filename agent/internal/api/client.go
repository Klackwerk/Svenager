// Package api implements the HTTP client for the Svenager agent API (v1).
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"github.com/klackwerk/svenager/agent/internal/config"
)

type Client struct {
	baseURL      string
	deviceToken  string
	agentVersion string
	http         *http.Client
}

func NewClient(cfg *config.Config, agentVersion string) *Client {
	return &Client{
		baseURL:      cfg.ServerURL,
		deviceToken:  cfg.DeviceToken,
		agentVersion: agentVersion,
		http:         &http.Client{Timeout: 30 * time.Second},
	}
}

// EnrollRequest is sent unauthenticated with a one-time enrollment token.
type EnrollRequest struct {
	EnrollmentToken string            `json:"enrollmentToken"`
	Hostname        string            `json:"hostname"`
	Facts           map[string]string `json:"facts,omitempty"`
}

type EnrollResponse struct {
	DeviceID    string `json:"deviceId"`
	DeviceToken string `json:"deviceToken"`
}

type CheckinRequest struct {
	AgentVersion string            `json:"agentVersion"`
	Facts        map[string]string `json:"facts,omitempty"`
}

type Job struct {
	ID      string          `json:"id"`
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

type CheckinResponse struct {
	// PollIntervalSeconds lets the server tune the fleet's check-in cadence.
	PollIntervalSeconds int  `json:"pollIntervalSeconds"`
	Job                 *Job `json:"job"`
}

func Enroll(ctx context.Context, serverURL string, req EnrollRequest) (*EnrollResponse, error) {
	var resp EnrollResponse
	c := &Client{baseURL: serverURL, http: &http.Client{Timeout: 30 * time.Second}}
	if err := c.post(ctx, "/api/v1/enroll", req, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

func (c *Client) Checkin(ctx context.Context, req CheckinRequest) (*CheckinResponse, error) {
	if req.AgentVersion == "" {
		req.AgentVersion = c.agentVersion
	}
	var resp CheckinResponse
	if err := c.post(ctx, "/api/v1/agent/checkin", req, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// RegisterRequest is the token-less enrollment pre-request: a pre-imaged
// device announces itself and polls until an admin approves it.
type RegisterRequest struct {
	RequestID string            `json:"requestId"`
	Hostname  string            `json:"hostname"`
	Facts     map[string]string `json:"facts,omitempty"`
}

type RegisterResponse struct {
	Status      string `json:"status"` // pending | approved | denied
	DeviceID    string `json:"deviceId,omitempty"`
	DeviceToken string `json:"deviceToken,omitempty"`
}

// Register performs one poll of the enrollment pre-request endpoint.
// "denied" arrives as HTTP 403 but is a valid answer, not an error.
func Register(ctx context.Context, serverURL string, req RegisterRequest) (*RegisterResponse, error) {
	payload, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost,
		serverURL+"/api/v1/enroll/request", bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusCreated, http.StatusAccepted, http.StatusForbidden:
		var out RegisterResponse
		if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
			return nil, err
		}
		return &out, nil
	default:
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("enroll request: %s: %s", resp.Status, bytes.TrimSpace(msg))
	}
}

// DialTunnel opens the reverse WebSocket tunnel of a remote-view session.
func (c *Client) DialTunnel(ctx context.Context, sessionID string) (*websocket.Conn, error) {
	header := http.Header{}
	if c.deviceToken != "" {
		header.Set("Authorization", "Bearer "+c.deviceToken)
	}
	conn, _, err := websocket.Dial(ctx, c.baseURL+"/api/v1/agent/tunnel/"+sessionID, &websocket.DialOptions{
		HTTPHeader: header,
		// Not c.http: its 30 s timeout would sever a long-lived tunnel.
		HTTPClient: &http.Client{},
	})
	if err != nil {
		return nil, err
	}
	conn.SetReadLimit(1 << 20)
	return conn, nil
}

// JobEvent reports job progress to the server.
type JobEvent struct {
	Event string `json:"event"` // started | log | finished
	// No omitempty: the first chunk's seq is 0 and must still be sent.
	Seq      int    `json:"seq"`
	Chunk    string `json:"chunk,omitempty"`
	ExitCode *int   `json:"exitCode,omitempty"`
	Error    string `json:"error,omitempty"`
}

func (c *Client) PostJobEvent(ctx context.Context, jobID string, event JobEvent) error {
	return c.post(ctx, "/api/v1/agent/jobs/"+jobID+"/events", event, nil)
}

// DownloadBundle streams the tar.gz bundle of one play's repository.
// The caller must Close the returned reader.
func (c *Client) DownloadBundle(ctx context.Context, jobID string, repoID int64) (io.ReadCloser, error) {
	url := fmt.Sprintf("%s/api/v1/agent/jobs/%s/bundles/%d", c.baseURL, jobID, repoID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.deviceToken)
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		return nil, fmt.Errorf("bundle download: %s: %s", resp.Status, bytes.TrimSpace(msg))
	}
	return resp.Body, nil
}

// FetchInstallFile downloads a public agent-distribution file (binary or
// signature) from the server, capped at 512 MB.
func (c *Client) FetchInstallFile(ctx context.Context, name string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/install/agent/"+name, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("download %s: %s: %s", name, resp.Status, bytes.TrimSpace(msg))
	}
	return io.ReadAll(io.LimitReader(resp.Body, 512<<20))
}

func (c *Client) post(ctx context.Context, path string, body, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.agentVersion != "" {
		req.Header.Set("User-Agent", "svenager-agent/"+c.agentVersion)
	}
	if c.deviceToken != "" {
		req.Header.Set("Authorization", "Bearer "+c.deviceToken)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("%s %s: %s: %s", http.MethodPost, path, resp.Status, bytes.TrimSpace(msg))
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}
