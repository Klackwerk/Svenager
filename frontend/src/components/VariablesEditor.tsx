import { useEffect, useRef, useState } from 'react'
import Alert from 'react-bootstrap/Alert'
import Button from 'react-bootstrap/Button'
import Form from 'react-bootstrap/Form'
import Table from 'react-bootstrap/Table'
import type { VariableEntry } from '../api/types'
import NavigationGuard from './NavigationGuard'

type Kind = 'text' | 'number' | 'boolean' | 'json'

interface Row {
  name: string
  /** text representation; secrets with null value show a placeholder */
  text: string
  kind: Kind
  secret: boolean
  /** true when a stored secret was not touched (keep server-side value) */
  untouchedSecret: boolean
}

function kindOf(value: unknown): Kind {
  if (typeof value === 'number') return 'number'
  if (typeof value === 'boolean') return 'boolean'
  if (value != null && typeof value === 'object') return 'json'
  return 'text'
}

function toRows(variables: VariableEntry[]): Row[] {
  return variables.map((v) => ({
    name: v.name,
    text: v.secret ? '' : typeof v.value === 'string' ? v.value : JSON.stringify(v.value),
    kind: v.secret ? 'text' : kindOf(v.value),
    secret: v.secret,
    untouchedSecret: v.secret,
  }))
}

/** Validation message for a row value, or null when it is fine. */
function validateRow(row: Row): string | null {
  if (row.untouchedSecret) return null
  switch (row.kind) {
    case 'number':
      return row.text.trim() !== '' && !Number.isNaN(Number(row.text.trim())) ? null : 'Must be a number.'
    case 'json':
      try {
        JSON.parse(row.text.trim())
        return null
      } catch {
        return 'Must be valid JSON, e.g. ["a", "b"] or {"key": 1}.'
      }
    default:
      return null
  }
}

/** Typed parse of a validated row — text stays a string, no guessing. */
function parseRow(row: Row): unknown {
  switch (row.kind) {
    case 'number':
      return Number(row.text.trim())
    case 'boolean':
      return row.text === 'true'
    case 'json':
      return JSON.parse(row.text.trim())
    default:
      return row.text
  }
}

interface Props {
  variables: VariableEntry[]
  /** Receives ONLY this editor's entries (custom + secrets). */
  onSave: (variables: VariableEntry[]) => void
  saving: boolean
  error: boolean
  /** Exposes the editor's current entries, for page-level merging. */
  register?: (getOwned: () => VariableEntry[]) => void
}

/**
 * Key/value editor for group and device variables. Variable names must match
 * the role variables from the Ansible sources; secret values are write-only.
 * Every value has an explicit type — nothing is guessed from the text.
 */
export default function VariablesEditor({ variables, onSave, saving, error, register }: Props) {
  const [rows, setRows] = useState<Row[]>(() => toRows(variables))
  const [dirty, setDirty] = useState(false)

  useEffect(() => {
    if (!dirty) setRows(toRows(variables))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [variables])

  const buildOwned = (): VariableEntry[] =>
    rows
      .filter((row) => row.name.trim() !== '' && validateRow(row) == null)
      .map((row) => ({
        name: row.name.trim(),
        secret: row.secret,
        value: row.secret && row.untouchedSecret ? null : parseRow(row),
      }))

  // Keep the page-level merge (other editor's save) seeing live state.
  const buildOwnedRef = useRef(buildOwned)
  buildOwnedRef.current = buildOwned
  useEffect(() => {
    register?.(() => buildOwnedRef.current())
  }, [register])

  const update = (index: number, patch: Partial<Row>) => {
    setDirty(true)
    setRows((current) =>
      current.map((row, i) =>
        i === index
          ? { ...row, ...patch, ...(patch.text !== undefined || patch.kind !== undefined ? { untouchedSecret: false } : {}) }
          : row,
      ),
    )
  }

  const invalidCount = rows.filter((row) => row.name.trim() !== '' && validateRow(row) != null).length

  const save = () => {
    onSave(buildOwned())
    setDirty(false)
  }

  return (
    <>
      <NavigationGuard when={dirty} />
      {error && <Alert variant="danger">Variables could not be saved. Please try again.</Alert>}
      <Table responsive size="sm" className="align-middle">
        <thead>
          <tr>
            <th style={{ width: '30%' }}>Name</th>
            <th>Value</th>
            <th style={{ width: '7rem' }}>Type</th>
            <th style={{ width: '5rem' }}>Secret</th>
            <th style={{ width: '3rem' }} aria-label="Actions" />
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={5} className="text-secondary">
                No variables set.
              </td>
            </tr>
          )}
          {rows.map((row, index) => {
            const problem = row.name.trim() !== '' ? validateRow(row) : null
            const errorId = `custom-var-${index}-error`
            return (
              <tr key={index}>
                <td>
                  <Form.Control
                    size="sm"
                    value={row.name}
                    placeholder="variable_name"
                    aria-label="Variable name"
                    onChange={(e) => update(index, { name: e.target.value })}
                  />
                </td>
                <td>
                  {row.kind === 'boolean' && !row.secret ? (
                    <Form.Select
                      size="sm"
                      aria-label="Variable value"
                      value={row.text === 'true' ? 'true' : 'false'}
                      onChange={(e) => update(index, { text: e.target.value })}
                    >
                      <option value="true">true</option>
                      <option value="false">false</option>
                    </Form.Select>
                  ) : (
                    <>
                      <Form.Control
                        size="sm"
                        type={row.secret ? 'password' : 'text'}
                        value={row.text}
                        placeholder={row.untouchedSecret ? '(unchanged)' : 'value'}
                        aria-label="Variable value"
                        isInvalid={!!problem}
                        aria-invalid={!!problem}
                        aria-describedby={problem ? errorId : undefined}
                        onChange={(e) => update(index, { text: e.target.value })}
                      />
                      {problem && (
                        <Form.Control.Feedback type="invalid" id={errorId} role="alert">
                          {problem}
                        </Form.Control.Feedback>
                      )}
                    </>
                  )}
                </td>
                <td>
                  <Form.Select
                    size="sm"
                    aria-label="Variable type"
                    value={row.secret ? 'text' : row.kind}
                    disabled={row.secret}
                    title={row.secret ? 'Secrets are always text' : undefined}
                    onChange={(e) => update(index, { kind: e.target.value as Kind })}
                  >
                    <option value="text">Text</option>
                    <option value="number">Number</option>
                    <option value="boolean">Boolean</option>
                    <option value="json">JSON</option>
                  </Form.Select>
                </td>
                <td className="text-center">
                  <Form.Check
                    type="switch"
                    aria-label="Secret"
                    checked={row.secret}
                    onChange={(e) =>
                      update(index, { secret: e.target.checked, kind: 'text', untouchedSecret: false })
                    }
                  />
                </td>
                <td>
                  <Button
                    size="sm"
                    variant="outline-danger"
                    aria-label={`Remove ${row.name || 'row'}`}
                    onClick={() => {
                      setDirty(true)
                      setRows((current) => current.filter((_, i) => i !== index))
                    }}
                  >
                    ×
                  </Button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </Table>
      {invalidCount > 0 && (
        <p className="text-danger small" role="status">
          Fix the highlighted {invalidCount === 1 ? 'value' : 'values'} to save.
        </p>
      )}
      <div className="d-flex gap-2">
        <Button
          size="sm"
          variant="outline-secondary"
          onClick={() => {
            setDirty(true)
            setRows((current) => [
              ...current,
              { name: '', text: '', kind: 'text', secret: false, untouchedSecret: false },
            ])
          }}
        >
          Add variable
        </Button>
        <Button size="sm" onClick={save} disabled={saving || !dirty || invalidCount > 0}>
          {saving ? 'Saving…' : 'Save variables'}
        </Button>
      </div>
    </>
  )
}
