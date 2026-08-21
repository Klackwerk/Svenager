import { useEffect, useId, useState } from 'react'
import Form from 'react-bootstrap/Form'
import ListGroup from 'react-bootstrap/ListGroup'
import { useDevices } from '../api/hooks'
import type { DeviceSummary } from '../api/types'

interface DeviceAutocompleteProps {
  /** Currently applied device filter, or null for "all devices". */
  selected: DeviceSummary | null
  onSelect: (device: DeviceSummary | null) => void
  placeholder?: string
}

/**
 * Combobox that searches devices by hostname/id as you type and applies the
 * picked device as a filter. Typing again clears the current selection.
 */
export default function DeviceAutocomplete({
  selected,
  onSelect,
  placeholder = 'Filter by device…',
}: DeviceAutocompleteProps) {
  const listId = useId()
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setQ(text.trim()), 300)
    return () => clearTimeout(timer)
  }, [text])

  const { data } = useDevices(q ? { q } : {}, 0, 8)
  const options = data?.items ?? []
  const showList = open && !selected && q !== ''

  return (
    <div className="position-relative" style={{ maxWidth: '15rem' }}>
      <Form.Control
        type="search"
        role="combobox"
        aria-label="Filter by device"
        aria-autocomplete="list"
        aria-expanded={showList}
        aria-controls={listId}
        placeholder={placeholder}
        value={selected ? selected.hostname : text}
        onChange={(e) => {
          if (selected) onSelect(null)
          setText(e.target.value)
          setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
      />
      {showList && (
        <ListGroup
          id={listId}
          role="listbox"
          aria-label="Matching devices"
          className="position-absolute w-100 shadow-sm"
          style={{ top: '100%', zIndex: 1050, maxHeight: '16rem', overflowY: 'auto' }}
        >
          {options.length === 0 && (
            <ListGroup.Item disabled className="text-secondary">
              No devices match "{q}"
            </ListGroup.Item>
          )}
          {options.map((device) => (
            <ListGroup.Item
              key={device.id}
              action
              role="option"
              aria-selected={false}
              // Fire before the input's blur closes the list.
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => {
                onSelect(device)
                setText('')
                setOpen(false)
              }}
            >
              {device.hostname}
              <span className="text-secondary small ms-2">
                {device.status === 'DISABLED' ? 'disabled' : device.online ? 'online' : 'offline'}
              </span>
            </ListGroup.Item>
          ))}
        </ListGroup>
      )}
    </div>
  )
}
