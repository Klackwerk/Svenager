interface Props {
  variant: 'success' | 'danger' | 'warning' | 'secondary' | 'primary'
  label: string
  count: number
}

/** A status count: colored dot + text label, never color alone. */
export default function StatusDot({ variant, label, count }: Props) {
  return (
    <span className="me-3 text-nowrap">
      <span
        className={`d-inline-block rounded-circle bg-${variant} me-1`}
        style={{ width: '0.6em', height: '0.6em' }}
        aria-hidden="true"
      />
      {count} {label}
    </span>
  )
}
