import { useEffect, useRef, type ReactNode } from 'react'
import Button from 'react-bootstrap/Button'
import Form from 'react-bootstrap/Form'

interface TableToolbarProps {
  search: string
  onSearchChange: (value: string) => void
  searchPlaceholder: string
  /** Rows visible after filtering vs. rows in total. */
  shown: number
  total: number
  /** Plural noun for the count, e.g. "devices". */
  noun: string
  filtersActive: boolean
  onClear: () => void
  /** Extra filter controls (selects) rendered next to the search field. */
  children?: ReactNode
}

/**
 * Shared filter bar for data tables: search-as-you-type, extra filters,
 * a visible result count (system status), one-click reset (easy reversal)
 * and "/" as a keyboard shortcut into the search field.
 */
export default function TableToolbar({
  search,
  onSearchChange,
  searchPlaceholder,
  shown,
  total,
  noun,
  filtersActive,
  onClear,
  children,
}: TableToolbarProps) {
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement
      if (event.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
        event.preventDefault()
        inputRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
      <Form.Control
        ref={inputRef}
        type="search"
        value={search}
        onChange={(e) => onSearchChange(e.target.value)}
        placeholder={searchPlaceholder}
        aria-label={searchPlaceholder}
        title="Shortcut: press / to search"
        style={{ maxWidth: '20rem' }}
      />
      {children}
      {filtersActive && (
        <Button size="sm" variant="outline-secondary" onClick={onClear}>
          Clear filters
        </Button>
      )}
      <span className="text-secondary ms-auto" role="status">
        {filtersActive ? `${shown} of ${total} ${noun}` : `${total} ${noun}`}
      </span>
    </div>
  )
}
