import Button from 'react-bootstrap/Button'
import Dropdown from 'react-bootstrap/Dropdown'
import Form from 'react-bootstrap/Form'

export interface FilterOption {
  value: string
  label: string
}

interface MultiSelectFilterProps {
  /** Short name shown on the toggle, e.g. "Status" or "Groups". */
  label: string
  options: FilterOption[]
  /** Selected values; empty means "no filter" (everything matches). */
  selected: string[]
  onChange: (values: string[]) => void
}

/**
 * Grafana-style filter dropdown: stays open while ticking checkboxes, the
 * toggle summarizes the current selection, empty selection means "all".
 */
export default function MultiSelectFilter({ label, options, selected, onChange }: MultiSelectFilterProps) {
  const toggleValue = (value: string, checked: boolean) =>
    onChange(checked ? [...selected, value] : selected.filter((v) => v !== value))

  const names = options.filter((o) => selected.includes(o.value)).map((o) => o.label)
  const summary = names.length === 0 ? 'All' : names.length <= 2 ? names.join(', ') : `${names.length} selected`

  return (
    <Dropdown autoClose="outside">
      <Dropdown.Toggle variant="outline-secondary" aria-label={`Filter by ${label.toLowerCase()}`}>
        {label}: {summary}
      </Dropdown.Toggle>
      <Dropdown.Menu>
        {options.map((option) => (
          <Dropdown.ItemText key={option.value} className="text-nowrap">
            <Form.Check
              id={`filter-${label}-${option.value}`}
              label={option.label}
              checked={selected.includes(option.value)}
              onChange={(e) => toggleValue(option.value, e.target.checked)}
            />
          </Dropdown.ItemText>
        ))}
        {selected.length > 0 && (
          <>
            <Dropdown.Divider />
            <Dropdown.ItemText>
              <Button size="sm" variant="link" className="p-0" onClick={() => onChange([])}>
                Clear filter
              </Button>
            </Dropdown.ItemText>
          </>
        )}
      </Dropdown.Menu>
    </Dropdown>
  )
}
