import type { SortState } from '../lib/useSort'

/** Clickable column header with an aria-sort state and direction marker. */
export default function SortHeader({
  label,
  sortKey,
  sort,
}: {
  label: string
  sortKey: string
  sort: SortState
}) {
  const active = sort.key === sortKey
  return (
    <th aria-sort={active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : undefined}>
      <button
        type="button"
        className="btn btn-link p-0 text-body fw-semibold text-decoration-none"
        onClick={() => sort.toggle(sortKey)}
      >
        {label}
        <span aria-hidden="true">{active ? (sort.direction === 'asc' ? ' ▲' : ' ▼') : ''}</span>
      </button>
    </th>
  )
}
