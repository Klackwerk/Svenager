import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import Form from 'react-bootstrap/Form'
import ListGroup from 'react-bootstrap/ListGroup'
import { useNavigate } from 'react-router-dom'
import { useGlobalSearch } from '../api/hooks'
import type { SearchResults } from '../api/types'

function GroupHeading({ children }: { children: ReactNode }) {
  return (
    <ListGroup.Item disabled className="text-secondary small text-uppercase py-1">
      {children}
    </ListGroup.Item>
  )
}

/**
 * App-wide search over devices, groups, Ansible roles and job ids with
 * grouped results. Ctrl/Cmd+K focuses it from anywhere ("/" stays with
 * the per-table search fields).
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
  const empty = data ? (Object.keys(data) as Array<keyof SearchResults>).every((k) => data[k].length === 0) : true

  const go = (path: string) => {
    navigate(path)
    setText('')
    setQ('')
    setOpen(false)
    inputRef.current?.blur()
  }

  const option = (key: string, path: string, children: ReactNode) => (
    <ListGroup.Item
      key={key}
      action
      role="option"
      aria-selected={false}
      onMouseDown={(e) => e.preventDefault()}
      onClick={() => go(path)}
    >
      {children}
    </ListGroup.Item>
  )

  return (
    <div className="position-relative">
      <Form.Control
        ref={inputRef}
        type="search"
        role="combobox"
        aria-label="Search devices, groups, roles and jobs"
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
          style={{
            top: '100%',
            left: 0,
            zIndex: 1050,
            width: '22rem',
            maxWidth: '90vw',
            maxHeight: '24rem',
            overflowY: 'auto',
          }}
        >
          {empty && (
            <ListGroup.Item disabled className="text-secondary">
              Nothing matches "{q}"
            </ListGroup.Item>
          )}
          {data.devices.length > 0 && <GroupHeading>Devices</GroupHeading>}
          {data.devices.map((device) =>
            option(
              device.id,
              `/devices/${device.id}`,
              <>
                {device.hostname}
                <span className="text-secondary small ms-2">
                  {device.status === 'DISABLED' ? 'disabled' : device.online ? 'online' : 'offline'}
                </span>
              </>,
            ),
          )}
          {data.groups.length > 0 && <GroupHeading>Groups</GroupHeading>}
          {data.groups.map((group) => option(group.id, `/groups/${group.id}`, group.name))}
          {data.roles.length > 0 && <GroupHeading>Ansible roles</GroupHeading>}
          {data.roles.map((role) =>
            option(
              role.id,
              `/sources?role=${encodeURIComponent(role.id)}`,
              <>
                {role.displayName}
                <span className="text-secondary small ms-2">
                  {role.repository}
                  {role.missing && ' · no longer in repository'}
                </span>
              </>,
            ),
          )}
          {data.jobs.length > 0 && <GroupHeading>Jobs</GroupHeading>}
          {data.jobs.map((job) =>
            option(
              job.id,
              `/jobs?job=${encodeURIComponent(job.id)}`,
              <>
                Job #{job.id.slice(0, 8)} on {job.hostname}
                <span className="text-secondary small ms-2">{job.status.toLowerCase()}</span>
              </>,
            ),
          )}
        </ListGroup>
      )}
    </div>
  )
}
