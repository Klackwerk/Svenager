import { useEffect, useRef, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Spinner from 'react-bootstrap/Spinner'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { FitAddon } from '@xterm/addon-fit'
import { Terminal } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'
import { useCloseRemoteSession, useDevice, useOpenShellSession, useRemoteSession } from '../api/hooks'

/**
 * Interactive shell on one device: opens a shell session, waits for the agent
 * to dial its reverse tunnel and then attaches xterm.js over the same audited,
 * time-limited websocket the remote view uses. The device runs a real PTY.
 */
export default function DeviceShell() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { data: device } = useDevice(id ?? null)
  const opened = useOpenShellSession(id ?? null)
  const closeSession = useCloseRemoteSession()
  const sessionId = opened.data?.sessionId ?? null
  const { data: session } = useRemoteSession(sessionId)

  const screenRef = useRef<HTMLDivElement>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const termRef = useRef<Terminal | null>(null)
  const [connected, setConnected] = useState(false)

  // Attach once the agent's tunnel is up. No cleanup here: the session
  // object changes on every status poll (AGENT_CONNECTED -> ACTIVE), and a
  // cleanup tied to it would tear down the socket we just opened. Teardown
  // is unmount-only, below.
  useEffect(() => {
    if (!session || wsRef.current || !screenRef.current) return
    if (session.status !== 'AGENT_CONNECTED') return

    const term = new Terminal({ convertEol: false, cursorBlink: true, fontSize: 13, scrollback: 5000 })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(screenRef.current)
    fit.fit()
    termRef.current = term

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${window.location.host}${session.wsPath}`, ['binary'])
    ws.binaryType = 'arraybuffer'
    const encoder = new TextEncoder()
    ws.onopen = () => {
      setConnected(true)
      term.focus()
    }
    ws.onmessage = (ev) => term.write(new Uint8Array(ev.data as ArrayBuffer))
    ws.onclose = () => setConnected(false)
    term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(encoder.encode(data))
    })
    wsRef.current = ws
  }, [session])

  // Teardown on unmount only (the broker closes the audited session as soon
  // as the viewer socket goes away).
  useEffect(() => {
    return () => {
      wsRef.current?.close()
      wsRef.current = null
      termRef.current?.dispose()
      termRef.current = null
    }
  }, [])

  const hostname = device?.hostname ?? session?.hostname ?? '…'
  const closed = session?.status === 'CLOSED'
  const waiting = !closed && !connected && !opened.isError

  const endSession = () => {
    if (sessionId) closeSession.mutate(sessionId)
  }

  const startNewSession = () => {
    wsRef.current?.close()
    wsRef.current = null
    termRef.current?.dispose()
    termRef.current = null
    setConnected(false)
    opened.refetch()
  }

  return (
    <>
      <nav aria-label="breadcrumb">
        <ol className="breadcrumb">
          <li className="breadcrumb-item">
            <Link to="/devices">Devices</Link>
          </li>
          <li className="breadcrumb-item">
            <Link to={`/devices/${id}`}>{hostname}</Link>
          </li>
          <li className="breadcrumb-item active" aria-current="page">
            Shell
          </li>
        </ol>
      </nav>

      <div className="d-flex align-items-center gap-2 mb-3 flex-wrap">
        <h1 className="h3 mb-0">Shell: {hostname}</h1>
        {connected && <Badge bg="success">Live</Badge>}
        <Button
          size="sm"
          variant="outline-danger"
          className="ms-auto"
          onClick={endSession}
          disabled={!sessionId || closed}
        >
          End session
        </Button>
      </div>

      {opened.isError && (
        <Alert variant="danger">
          The shell session could not be opened.{' '}
          <Button size="sm" variant="outline-danger" onClick={() => opened.refetch()}>
            Try again
          </Button>
        </Alert>
      )}

      {closed && (
        <Alert variant="secondary">
          <Alert.Heading className="h6">Session ended</Alert.Heading>
          <p className="mb-2">{session?.closeReason ?? 'The shell session is over.'}</p>
          <div className="d-flex gap-2">
            <Button size="sm" onClick={startNewSession}>
              Start a new session
            </Button>
            <Button size="sm" variant="outline-secondary" onClick={() => navigate(`/devices/${id}`)}>
              Back to device
            </Button>
          </div>
        </Alert>
      )}

      {waiting && (
        <Alert variant="info" className="d-flex align-items-center gap-3">
          <Spinner size="sm" role="status" aria-label="Connecting" />
          <div>
            {session?.status === 'AGENT_CONNECTED'
              ? 'Device connected — attaching the terminal…'
              : 'Waiting for the device to pick up the shell. It fetches work at its next check-in (about once a minute).'}
            {device && !device.online && (
              <div className="small text-danger mt-1">
                This device looks offline — the session will time out if it does not check in.
              </div>
            )}
          </div>
        </Alert>
      )}

      <div
        ref={screenRef}
        className="border rounded"
        style={{ height: '70vh', overflow: 'hidden', background: '#000', display: closed ? 'none' : undefined }}
        aria-label="Device shell"
      />
    </>
  )
}
