import { useEffect, useId, useRef, useState } from 'react'
import Form from 'react-bootstrap/Form'
import ListGroup from 'react-bootstrap/ListGroup'
import { useNavigate } from 'react-router-dom'
import { useGlobalSearch } from '../api/hooks'

/**
 * Navbar-wide search over devices, groups and job ids with grouped
 * results. Ctrl/Cmd+K focuses it from anywhere ("/" stays with the
 * per-table search fields).
 */
export default function GlobalSearch() {
  const listId = useId()
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement>(null)
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setQ(text.trim()), 250)
    return () => clearTimeout(timer)
  }, [text])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        inputRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const { data } = useGlobalSearch(q)
  const showList = open && q.length >= 2
  const empty =
    (data?.devices.length ?? 0) === 0 && (data?.groups.length ?? 0) === 0 && (data?.jobs.length ?? 0) === 0

  const go = (path: string) => {
    navigate(path)
    setText('')
    setQ('')
    setOpen(false)
    inputRef.current?.blur()
  }

  return (
    <div className="position-relative" style={{ minWidth: '14rem' }} data-bs-theme="light">
      <Form.Control
        ref={inputRef}
        type="search"
        role="combobox"
        aria-label="Search devices, groups and jobs"
        aria-autocomplete="list"
        aria-expanded={showList}
        aria-controls={listId}
        placeholder="Search… (Ctrl+K)"
        size="sm"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
      />
      {showList && data && (
        <ListGroup
          id={listId}
          role="listbox"
          aria-label="Search results"
          className="position-absolute shadow"
          style={{ top: '100%', right: 0, zIndex: 1050, minWidth: '20rem', maxHeight: '24rem', overflowY: 'auto' }}
        >
          {empty && (
            <ListGroup.Item disabled className="text-secondary">
              Nothing matches "{q}"
            </ListGroup.Item>
          )}
          {data.devices.length > 0 && (
            <ListGroup.Item disabled className="text-secondary small text-uppercase py-1">
              Devices
            </ListGroup.Item>
          )}
          {data.devices.map((device) => (
            <ListGroup.Item
              key={device.id}
              action
              role="option"
              aria-selected={false}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => go(`/devices/${device.id}`)}
            >
              {device.hostname}
              <span className="text-secondary small ms-2">
                {device.status === 'DISABLED' ? 'disabled' : device.online ? 'online' : 'offline'}
              </span>
            </ListGroup.Item>
          ))}
          {data.groups.length > 0 && (
            <ListGroup.Item disabled className="text-secondary small text-uppercase py-1">
              Groups
            </ListGroup.Item>
          )}
          {data.groups.map((group) => (
            <ListGroup.Item
              key={group.id}
              action
              role="option"
              aria-selected={false}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => go(`/groups/${group.id}`)}
            >
              {group.name}
            </ListGroup.Item>
          ))}
          {data.jobs.length > 0 && (
            <ListGroup.Item disabled className="text-secondary small text-uppercase py-1">
              Jobs
            </ListGroup.Item>
          )}
          {data.jobs.map((job) => (
            <ListGroup.Item
              key={job.id}
              action
              role="option"
              aria-selected={false}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => go(`/jobs/${job.id}`)}
            >
              Job #{job.id.slice(0, 8)} on {job.hostname}
              <span className="text-secondary small ms-2">{job.status.toLowerCase()}</span>
            </ListGroup.Item>
          ))}
        </ListGroup>
      )}
    </div>
  )
}
