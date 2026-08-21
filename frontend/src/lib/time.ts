const UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
]

const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

/** "3 minutes ago" — or a dash when the timestamp is missing. */
export function relativeTime(iso: string | null): string {
  if (!iso) return '—'
  const seconds = (Date.parse(iso) - Date.now()) / 1000
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) {
      return formatter.format(Math.round(seconds / size), unit)
    }
  }
  return formatter.format(Math.round(seconds), 'second')
}

export function absoluteTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : ''
}
