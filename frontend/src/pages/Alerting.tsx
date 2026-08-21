import { useState, type FormEvent } from 'react'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Col from 'react-bootstrap/Col'
import Form from 'react-bootstrap/Form'
import Modal from 'react-bootstrap/Modal'
import Row from 'react-bootstrap/Row'
import Spinner from 'react-bootstrap/Spinner'
import Table from 'react-bootstrap/Table'
import {
  useCreateNotificationChannel,
  useDeleteNotificationChannel,
  useNotificationChannels,
  useTestNotificationChannel,
  useUpdateNotificationChannel,
} from '../api/hooks'
import type { NotificationChannelInfo } from '../api/types'
import { useToast } from '../components/ToastProvider'

export default function Alerting() {
  const { data: channels, isLoading, isError } = useNotificationChannels()
  const createChannel = useCreateNotificationChannel()
  const updateChannel = useUpdateNotificationChannel()
  const deleteChannel = useDeleteNotificationChannel()
  const testChannel = useTestNotificationChannel()
  const toast = useToast()

  const [name, setName] = useState('')
  const [type, setType] = useState<NotificationChannelInfo['type']>('EMAIL')
  const [target, setTarget] = useState('')
  const [toDelete, setToDelete] = useState<NotificationChannelInfo | null>(null)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    createChannel.mutate(
      { name: name.trim(), type, target: target.trim() },
      {
        onSuccess: () => {
          setName('')
          setTarget('')
        },
      },
    )
  }

  return (
    <>
      <h1 className="h3 mb-3">Alerting</h1>
      <p className="text-secondary">
        Where Svenager reports problems: devices offline past their threshold (one alert per outage, one on
        recovery), configuration applies that keep failing, and repository syncs that break. Email needs an SMTP
        server configured on the server (<code>spring.mail.host</code>); webhooks receive a JSON POST.
      </p>

      <Card className="mb-4">
        <Card.Body>
          <Card.Title className="h6">New channel</Card.Title>
          {createChannel.isError && (
            <Alert variant="danger">{(createChannel.error as Error).message || 'The channel could not be created.'}</Alert>
          )}
          <Form onSubmit={submit}>
            <Row className="g-3 align-items-end">
              <Col xs={12} md={3}>
                <Form.Group controlId="channel-name">
                  <Form.Label>Name</Form.Label>
                  <Form.Control
                    value={name}
                    placeholder="e.g. Ops mailbox"
                    onChange={(e) => setName(e.target.value)}
                    required
                  />
                </Form.Group>
              </Col>
              <Col xs={12} md={2}>
                <Form.Group controlId="channel-type">
                  <Form.Label>Type</Form.Label>
                  <Form.Select
                    value={type}
                    onChange={(e) => setType(e.target.value as NotificationChannelInfo['type'])}
                  >
                    <option value="EMAIL">Email</option>
                    <option value="WEBHOOK">Webhook</option>
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col xs={12} md={5}>
                <Form.Group controlId="channel-target">
                  <Form.Label>{type === 'EMAIL' ? 'Email address' : 'Webhook URL'}</Form.Label>
                  <Form.Control
                    type={type === 'EMAIL' ? 'email' : 'url'}
                    value={target}
                    placeholder={type === 'EMAIL' ? 'ops@example.org' : 'https://hooks.example.org/svenager'}
                    onChange={(e) => setTarget(e.target.value)}
                    required
                  />
                </Form.Group>
              </Col>
              <Col xs={12} md={2}>
                <Button type="submit" className="w-100" disabled={createChannel.isPending}>
                  {createChannel.isPending ? 'Creating…' : 'Create'}
                </Button>
              </Col>
            </Row>
          </Form>
        </Card.Body>
      </Card>

      {isLoading && <Spinner role="status" aria-label="Loading channels" />}
      {isError && <Alert variant="danger">Channels could not be loaded. Retrying automatically…</Alert>}

      {channels && (
        <Table hover responsive className="align-middle">
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Target</th>
              <th>Enabled</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {channels.length === 0 && (
              <tr>
                <td colSpan={5} className="text-secondary">
                  No channels yet — alerts go nowhere until you add one above.
                </td>
              </tr>
            )}
            {channels.map((channel) => (
              <tr key={channel.id} className={channel.enabled ? undefined : 'opacity-50'}>
                <td className="fw-medium">{channel.name}</td>
                <td>
                  <Badge bg={channel.type === 'EMAIL' ? 'primary' : 'info'} text={channel.type === 'EMAIL' ? undefined : 'dark'}>
                    {channel.type === 'EMAIL' ? 'Email' : 'Webhook'}
                  </Badge>
                </td>
                <td>
                  <code>{channel.target}</code>
                </td>
                <td>
                  <Form.Check
                    type="switch"
                    id={`channel-enabled-${channel.id}`}
                    aria-label={`Enable ${channel.name}`}
                    checked={channel.enabled}
                    disabled={updateChannel.isPending && updateChannel.variables?.id === channel.id}
                    onChange={(e) => updateChannel.mutate({ id: channel.id, enabled: e.target.checked })}
                  />
                </td>
                <td className="text-end text-nowrap">
                  <Button
                    size="sm"
                    variant="outline-secondary"
                    className="me-2"
                    disabled={testChannel.isPending && testChannel.variables === channel.id}
                    onClick={() =>
                      testChannel.mutate(channel.id, {
                        onSuccess: () =>
                          toast({ variant: 'success', text: `Test alert sent via ${channel.name}.` }),
                      })
                    }
                  >
                    {testChannel.isPending && testChannel.variables === channel.id ? 'Sending…' : 'Send test alert'}
                  </Button>
                  <Button size="sm" variant="outline-danger" onClick={() => setToDelete(channel)}>
                    Remove
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <Modal show={toDelete != null} onHide={() => setToDelete(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>Remove channel?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <strong>{toDelete?.name}</strong> will no longer receive any alerts. This cannot be undone.
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setToDelete(null)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={deleteChannel.isPending}
            onClick={() => toDelete && deleteChannel.mutate(toDelete.id, { onSettled: () => setToDelete(null) })}
          >
            {deleteChannel.isPending ? 'Removing…' : 'Remove channel'}
          </Button>
        </Modal.Footer>
      </Modal>
    </>
  )
}
