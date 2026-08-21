import Card from 'react-bootstrap/Card'

interface Props {
  label: string
  value: string
  /** Small line under the value, e.g. a breakdown with status dots. */
  detail?: React.ReactNode
}

/** A hero number with its label — numbers wear text colors, not status colors. */
export default function StatTile({ label, value, detail }: Props) {
  return (
    <Card className="h-100">
      <Card.Body>
        <div className="text-secondary small">{label}</div>
        <div className="fs-2 fw-semibold lh-sm">{value}</div>
        {detail && <div className="small mt-1">{detail}</div>}
      </Card.Body>
    </Card>
  )
}
