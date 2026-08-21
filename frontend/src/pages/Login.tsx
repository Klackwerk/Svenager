import { useState, type FormEvent } from 'react'
import Alert from 'react-bootstrap/Alert'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Form from 'react-bootstrap/Form'
import { useLogin, useLoginOptions } from '../api/hooks'
import { ApiError } from '../api/client'

/** One-time read of the SSO error the server redirects back with. */
function ssoErrorFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get('ssoError')
}

export default function Login() {
  const login = useLogin()
  const { data: options } = useLoginOptions()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [ssoError] = useState(ssoErrorFromUrl)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    login.mutate({ username, password })
  }

  const errorMessage =
    login.error instanceof ApiError && login.error.status === 401
      ? 'Wrong username or password. Please try again.'
      : login.error instanceof ApiError && login.error.status === 429
        ? 'Too many attempts — wait a minute and try again.'
        : login.error
          ? 'The server could not be reached. Please try again in a moment.'
          : null

  return (
    <div className="d-flex justify-content-center align-items-center min-vh-100 bg-body-tertiary px-3">
      <Card className="w-100 shadow-sm" style={{ maxWidth: '24rem' }}>
        <Card.Body className="p-4">
          <h1 className="h4 mb-1">Svenager</h1>
          <p className="text-secondary mb-4">Sign in to manage your devices</p>
          {errorMessage && <Alert variant="danger">{errorMessage}</Alert>}
          {ssoError && <Alert variant="danger">Single sign-on failed: {ssoError}</Alert>}
          <Form onSubmit={submit}>
            <Form.Group className="mb-3" controlId="username">
              <Form.Label>Username</Form.Label>
              <Form.Control
                autoFocus
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
              />
            </Form.Group>
            <Form.Group className="mb-4" controlId="password">
              <Form.Label>Password</Form.Label>
              <Form.Control
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </Form.Group>
            <Button type="submit" className="w-100" disabled={login.isPending}>
              {login.isPending ? 'Signing in…' : 'Sign in'}
            </Button>
          </Form>
          {options?.sso && options.ssoUrl && (
            <>
              <div className="d-flex align-items-center gap-2 my-3 text-secondary">
                <hr className="flex-grow-1" />
                <span className="small">or</span>
                <hr className="flex-grow-1" />
              </div>
              <Button variant="outline-primary" className="w-100" href={options.ssoUrl}>
                Continue with {options.ssoLabel ?? 'single sign-on'}
              </Button>
            </>
          )}
        </Card.Body>
      </Card>
    </div>
  )
}
