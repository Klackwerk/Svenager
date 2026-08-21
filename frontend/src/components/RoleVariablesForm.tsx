import { useEffect, useMemo, useRef, useState } from 'react'
import Accordion from 'react-bootstrap/Accordion'
import Alert from 'react-bootstrap/Alert'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Form from 'react-bootstrap/Form'
import type { ArgumentSpecOption, RoleInfo, VariableEntry } from '../api/types'
import NavigationGuard from './NavigationGuard'

interface FieldSpec {
  name: string
  option?: ArgumentSpecOption
  defaultValue: unknown
}

function fieldsOf(role: RoleInfo): FieldSpec[] {
  const names = new Set([...Object.keys(role.argumentSpec ?? {}), ...Object.keys(role.defaults ?? {})])
  return [...names].sort().map((name) => ({
    name,
    option: role.argumentSpec?.[name],
    defaultValue: role.argumentSpec?.[name]?.default ?? role.defaults?.[name],
  }))
}

/** Variable names covered by the typed forms of these roles. */
export function managedVariableNames(roles: RoleInfo[]): Set<string> {
  return new Set(roles.flatMap((role) => fieldsOf(role).map((f) => f.name)))
}

function toText(value: unknown): string {
  if (value == null) return ''
  return typeof value === 'string' ? value : JSON.stringify(value)
}

/** Declared spec type, falling back to the default value's shape. */
function effectiveType(field: FieldSpec): string {
  if (field.option?.type) return field.option.type
  const d = field.defaultValue
  if (typeof d === 'boolean') return 'bool'
  if (typeof d === 'number') return 'float'
  if (d != null && typeof d === 'object') return 'json'
  return 'str'
}

/** Validation message for an override, or null when it is fine. */
function validate(text: string, type: string, option?: ArgumentSpecOption): string | null {
  const trimmed = text.trim()
  if (option?.required && trimmed === '') return 'This value is required.'
  switch (type) {
    case 'int':
      return /^-?\d+$/.test(trimmed) ? null : 'Must be a whole number (or use the default).'
    case 'float':
      return trimmed !== '' && !Number.isNaN(Number(trimmed))
        ? null
        : 'Must be a number (or use the default).'
    case 'json':
      try {
        JSON.parse(trimmed)
        return null
      } catch {
        return 'Must be valid JSON, e.g. ["a", "b"] or {"key": 1}.'
      }
    default:
      return null
  }
}

/** Typed parse of a validated override — strings stay strings. */
function parseValue(text: string, type: string): unknown {
  const trimmed = text.trim()
  switch (type) {
    case 'bool':
      return trimmed === 'true'
    case 'int':
    case 'float':
      return Number(trimmed)
    case 'json':
      return JSON.parse(trimmed)
    default:
      return text
  }
}

function describe(option?: ArgumentSpecOption): string {
  if (!option?.description) return ''
  return Array.isArray(option.description) ? option.description.join(' ') : option.description
}

interface Props {
  roles: RoleInfo[]
  /** All current variable entries of the scope (secrets included). */
  variables: VariableEntry[]
  /** Receives ONLY the managed, non-secret overrides of this form. */
  onSave: (owned: VariableEntry[]) => void
  saving: boolean
  error: boolean
  /** Exposes the form's current owned entries, for page-level merging. */
  register?: (getOwned: () => VariableEntry[]) => void
}

/**
 * Auto-generated variable forms: one section per role that will run in this
 * scope, every field pre-filled with the role's default and overridable in
 * place (recognition over recall — no YAML, no remembering variable names).
 * Only actual overrides are stored; everything else keeps following the
 * role's default. Values are validated inline against the argument spec.
 */
export default function RoleVariablesForm({ roles, variables, onSave, saving, error, register }: Props) {
  const managedNames = useMemo(() => managedVariableNames(roles), [roles])

  const [overrides, setOverrides] = useState<Record<string, string>>({})
  const [dirty, setDirty] = useState(false)

  useEffect(() => {
    if (dirty) return
    const managed = variables.filter((v) => !v.secret && managedNames.has(v.name))
    setOverrides(Object.fromEntries(managed.map((v) => [v.name, toText(v.value)])))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [variables, roles])

  const fieldByName = useMemo(() => {
    const map = new Map<string, FieldSpec>()
    roles.forEach((role) => fieldsOf(role).forEach((f) => map.set(f.name, f)))
    return map
  }, [roles])

  const problems = useMemo(() => {
    const map = new Map<string, string>()
    Object.entries(overrides).forEach(([name, text]) => {
      const field = fieldByName.get(name)
      if (!field) return
      const message = validate(text, effectiveType(field), field.option)
      if (message) map.set(name, message)
    })
    return map
  }, [overrides, fieldByName])

  const buildOwned = (): VariableEntry[] =>
    Object.entries(overrides)
      .filter(([name]) => fieldByName.has(name) && !problems.has(name))
      .map(([name, text]) => ({
        name,
        secret: false,
        value: parseValue(text, effectiveType(fieldByName.get(name)!)),
      }))

  // Keep the page-level merge (other editor's save) seeing live state.
  const buildOwnedRef = useRef(buildOwned)
  buildOwnedRef.current = buildOwned
  useEffect(() => {
    register?.(() => buildOwnedRef.current())
  }, [register])

  const setOverride = (name: string, text: string) => {
    setDirty(true)
    setOverrides((current) => ({ ...current, [name]: text }))
  }
  const reset = (name: string) => {
    setDirty(true)
    setOverrides((current) => {
      const next = { ...current }
      delete next[name]
      return next
    })
  }

  const save = () => {
    onSave(buildOwned())
    setDirty(false)
  }

  const sections = roles
    .map((role) => ({ role, fields: fieldsOf(role) }))
    .filter(({ fields }) => fields.length > 0)

  if (sections.length === 0) {
    return (
      <p className="text-secondary mb-0">
        No configurable role variables yet — they appear here automatically once roles are assigned.
      </p>
    )
  }

  const openSections = sections
    .filter(({ fields }) => fields.some((f) => f.name in overrides))
    .map(({ role }) => String(role.id))

  return (
    <>
      <NavigationGuard when={dirty} />
      {error && <Alert variant="danger">Variables could not be saved. Please try again.</Alert>}
      <Accordion alwaysOpen defaultActiveKey={openSections} className="mb-3">
        {sections.map(({ role, fields }) => {
          const overriddenCount = fields.filter((f) => f.name in overrides).length
          return (
            <Accordion.Item key={role.id} eventKey={String(role.id)}>
              <Accordion.Header>
                <span className="d-flex align-items-center gap-2 flex-wrap me-2 w-100">
                  <span className="fw-medium">{role.displayName}</span>
                  <span className="text-secondary small">{role.repository}</span>
                  <span className="ms-auto d-flex gap-2">
                    {overriddenCount > 0 ? (
                      <Badge bg="primary">{overriddenCount} overridden</Badge>
                    ) : (
                      <Badge bg="secondary">all defaults</Badge>
                    )}
                    <Badge bg="light" text="dark">
                      {fields.length} {fields.length === 1 ? 'variable' : 'variables'}
                    </Badge>
                  </span>
                </span>
              </Accordion.Header>
              <Accordion.Body>
                {fields.map((field) => {
                  const overridden = field.name in overrides
                  const description = describe(field.option)
                  const type = effectiveType(field)
                  const currentText = overridden ? overrides[field.name] : toText(field.defaultValue)
                  const problem = overridden ? problems.get(field.name) : undefined
                  const errorId = `var-${role.id}-${field.name}-error`
                  const controlId = `var-${role.id}-${field.name}`
                  return (
                    <Form.Group key={field.name} className="mt-2" controlId={controlId}>
                      <Form.Label className="mb-1 d-flex align-items-center gap-2">
                        <code>{field.name}</code>
                        <Badge bg={overridden ? 'primary' : 'secondary'}>
                          {overridden ? 'override' : 'default'}
                        </Badge>
                        {field.option?.required && (
                          <Badge bg="warning" text="dark">
                            required
                          </Badge>
                        )}
                        {overridden && (
                          <Button size="sm" variant="link" className="p-0" onClick={() => reset(field.name)}>
                            use default
                          </Button>
                        )}
                      </Form.Label>
                      {type === 'bool' ? (
                        <Form.Check
                          type="switch"
                          aria-label={field.name}
                          checked={overridden ? overrides[field.name] === 'true' : Boolean(field.defaultValue)}
                          onChange={(e) => setOverride(field.name, String(e.target.checked))}
                        />
                      ) : field.option?.choices?.length ? (
                        <Form.Select
                          size="sm"
                          value={currentText}
                          onChange={(e) => setOverride(field.name, e.target.value)}
                        >
                          {field.option.choices.map((choice) => (
                            <option key={String(choice)} value={toText(choice)}>
                              {toText(choice)}
                              {toText(choice) === toText(field.defaultValue) ? ' (default)' : ''}
                            </option>
                          ))}
                        </Form.Select>
                      ) : (
                        <>
                          <Form.Control
                            size="sm"
                            type={type === 'int' || type === 'float' ? 'number' : 'text'}
                            value={currentText}
                            isInvalid={!!problem}
                            aria-invalid={!!problem}
                            aria-describedby={problem ? errorId : undefined}
                            onChange={(e) => setOverride(field.name, e.target.value)}
                          />
                          {problem && (
                            <Form.Control.Feedback type="invalid" id={errorId} role="alert">
                              {problem}
                            </Form.Control.Feedback>
                          )}
                        </>
                      )}
                      {description && <Form.Text className="text-secondary">{description}</Form.Text>}
                    </Form.Group>
                  )
                })}
              </Accordion.Body>
            </Accordion.Item>
          )
        })}
      </Accordion>
      {problems.size > 0 && (
        <p className="text-danger small" role="status">
          Fix the highlighted {problems.size === 1 ? 'value' : 'values'} to save.
        </p>
      )}
      <Button size="sm" onClick={save} disabled={saving || !dirty || problems.size > 0}>
        {saving ? 'Saving…' : 'Save role variables'}
      </Button>
    </>
  )
}
