import { useState, type FormEvent } from 'react'
import Alert from 'react-bootstrap/Alert'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Col from 'react-bootstrap/Col'
import Form from 'react-bootstrap/Form'
import Row from 'react-bootstrap/Row'
import Spinner from 'react-bootstrap/Spinner'
import { Link, useNavigate } from 'react-router-dom'
import { useCreateGroup, useGroups } from '../api/hooks'

export default function Groups() {
  const { data: groups, isLoading, isError } = useGroups()
  const createGroup = useCreateGroup()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const submit = (event: FormEvent) => {
    event.preventDefault()
    createGroup.mutate(
      { name: name.trim(), description: description.trim() },
      {
        onSuccess: (group) => {
          setName('')
          setDescription('')
          navigate(`/groups/${group.id}`)
        },
      },
    )
  }

  return (
    <>
      <h1 className="h3 mb-3">Groups</h1>
      <p className="text-secondary">
        Groups decide what runs on a device: every device in a group executes the group's assigned roles with the
        group's variables.
      </p>

      <Card className="mb-4">
        <Card.Body>
          <Card.Title className="h6">New group</Card.Title>
          {createGroup.isError && (
            <Alert variant="danger">The group could not be created — is the name already in use?</Alert>
          )}
          <Form onSubmit={submit}>
            <Row className="g-3 align-items-end">
              <Col xs={12} md={4}>
                <Form.Group controlId="group-name">
                  <Form.Label>Name</Form.Label>
                  <Form.Control
                    placeholder="e.g. Self-service terminals"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    required
                  />
                </Form.Group>
              </Col>
              <Col xs={12} md={6}>
                <Form.Group controlId="group-description">
                  <Form.Label>Description</Form.Label>
                  <Form.Control
                    placeholder="What are these devices for?"
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                  />
                </Form.Group>
              </Col>
              <Col xs={12} md={2}>
                <Button type="submit" className="w-100" disabled={createGroup.isPending}>
                  {createGroup.isPending ? 'Creating…' : 'Create'}
                </Button>
              </Col>
            </Row>
          </Form>
        </Card.Body>
      </Card>

      {isLoading && <Spinner role="status" aria-label="Loading groups" />}
      {isError && <Alert variant="danger">Groups could not be loaded. Retrying automatically…</Alert>}
      {groups && groups.length === 0 && <Alert variant="secondary">No groups yet — create the first one above.</Alert>}

      <Row xs={1} md={2} xl={3} className="g-3">
        {groups?.map((group) => (
          <Col key={group.id}>
            <Card as={Link} to={`/groups/${group.id}`} className="h-100 text-decoration-none text-body">
              <Card.Body>
                <Card.Title className="h6">{group.name}</Card.Title>
                {group.description && <Card.Text className="text-secondary">{group.description}</Card.Text>}
                <Card.Text className="mb-0 small text-secondary">
                  {group.deviceCount} {group.deviceCount === 1 ? 'device' : 'devices'} · {group.roleCount}{' '}
                  {group.roleCount === 1 ? 'role' : 'roles'}
                </Card.Text>
              </Card.Body>
            </Card>
          </Col>
        ))}
      </Row>
    </>
  )
}
