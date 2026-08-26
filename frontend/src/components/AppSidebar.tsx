import { useEffect, useState, type ComponentType } from 'react'
import Badge from 'react-bootstrap/Badge'
import Dropdown from 'react-bootstrap/Dropdown'
import Form from 'react-bootstrap/Form'
import Nav from 'react-bootstrap/Nav'
import Navbar from 'react-bootstrap/Navbar'
import Offcanvas from 'react-bootstrap/Offcanvas'
import {
  Bell,
  BoxArrowRight,
  CircleHalf,
  Collection,
  Git,
  JournalText,
  ListCheck,
  MoonStarsFill,
  PcDisplay,
  People,
  PersonCircle,
  QrCode,
  Speedometer2,
  SunFill,
  type Icon,
} from 'react-bootstrap-icons'
import { NavLink, useLocation } from 'react-router-dom'
import { useLogout } from '../api/hooks'
import type { AuthUser } from '../api/types'
import { useExpertMode } from '../lib/expertMode'
import type { ThemePreference } from '../lib/theme'
import GlobalSearch from './GlobalSearch'

const THEMES: Array<{ value: ThemePreference; label: string; icon: Icon }> = [
  { value: 'light', label: 'Light', icon: SunFill },
  { value: 'dark', label: 'Dark', icon: MoonStarsFill },
  { value: 'system', label: 'System', icon: CircleHalf },
]

interface NavItem {
  to: string
  label: string
  icon: ComponentType<{ size?: number; 'aria-hidden'?: boolean }>
}

interface NavSection {
  title?: string
  adminOnly?: boolean
  items: NavItem[]
}

// Grouped by what operators do: watch the fleet, manage its members,
// configure and run automation, administer the installation.
const SECTIONS: NavSection[] = [
  { items: [{ to: '/', label: 'Dashboard', icon: Speedometer2 }] },
  {
    title: 'Fleet',
    items: [
      { to: '/devices', label: 'Devices', icon: PcDisplay },
      { to: '/groups', label: 'Groups', icon: Collection },
      { to: '/enrollment', label: 'Enrollment', icon: QrCode },
    ],
  },
  {
    title: 'Automation',
    items: [
      { to: '/sources', label: 'Ansible sources', icon: Git },
      { to: '/jobs', label: 'Jobs', icon: ListCheck },
    ],
  },
  {
    title: 'Administration',
    adminOnly: true,
    items: [
      { to: '/alerting', label: 'Alerting', icon: Bell },
      { to: '/users', label: 'Users', icon: People },
      { to: '/audit', label: 'Audit log', icon: JournalText },
    ],
  },
]

interface AppSidebarProps {
  user: AuthUser
  theme: { preference: ThemePreference; setPreference: (preference: ThemePreference) => void }
}

/**
 * Left-hand navigation on lg+ screens (brand, search, grouped links,
 * account menu). Below lg it collapses into a sticky top bar whose
 * hamburger opens the same content as an offcanvas drawer.
 */
export default function AppSidebar({ user, theme }: AppSidebarProps) {
  const logout = useLogout()
  const location = useLocation()
  const { expert, setExpert } = useExpertMode()
  const [expanded, setExpanded] = useState(false)
  const isAdmin = user.roles.includes('ROLE_ADMIN')

  // Close the mobile drawer after navigating from a link or search hit.
  useEffect(() => setExpanded(false), [location])

  return (
    <Navbar
      expand="lg"
      expanded={expanded}
      onToggle={setExpanded}
      className="app-sidebar bg-body-tertiary sticky-top"
      aria-label="Main navigation"
    >
      <div className="app-sidebar-header d-flex align-items-center justify-content-between">
        <Navbar.Brand as={NavLink} to="/" className="d-flex align-items-center gap-2 me-0">
          <img src="/favicon.svg" alt="" width={22} height={21} />
          Svenager
        </Navbar.Brand>
        <Navbar.Toggle aria-controls="main-nav" />
      </div>
      <Navbar.Offcanvas id="main-nav" placement="start" aria-labelledby="main-nav-label">
        <Offcanvas.Header closeButton>
          <Offcanvas.Title id="main-nav-label" className="d-flex align-items-center gap-2">
            <img src="/favicon.svg" alt="" width={22} height={21} />
            Svenager
          </Offcanvas.Title>
        </Offcanvas.Header>
        <Offcanvas.Body className="app-sidebar-body">
          <div className="app-sidebar-search">
            <GlobalSearch />
          </div>
          <div className="app-sidebar-nav">
            {SECTIONS.filter((section) => !section.adminOnly || isAdmin).map((section, index) => (
              <div key={section.title ?? index} className="mb-3">
                {section.title && (
                  <div className="app-sidebar-section text-secondary text-uppercase small fw-semibold">
                    {section.title}
                  </div>
                )}
                <Nav variant="pills" className="flex-column gap-1">
                  {section.items.map(({ to, label, icon: ItemIcon }) => (
                    <Nav.Link
                      key={to}
                      as={NavLink}
                      to={to}
                      end={to === '/'}
                      className="d-flex align-items-center gap-2 py-1"
                    >
                      <ItemIcon size={16} aria-hidden />
                      {label}
                    </Nav.Link>
                  ))}
                </Nav>
              </div>
            ))}
          </div>
          <div className="app-sidebar-footer border-top pt-2">
            <Dropdown drop="up">
              <Dropdown.Toggle
                variant="link"
                id="account-menu"
                className="w-100 d-flex align-items-center gap-2 text-body text-decoration-none px-2"
              >
                <PersonCircle size={18} aria-hidden />
                <span className="flex-grow-1 text-start text-truncate">{user.username}</span>
                {expert && (
                  <Badge bg="secondary" title="Expert details are shown">
                    Expert
                  </Badge>
                )}
              </Dropdown.Toggle>
              <Dropdown.Menu className="w-100 shadow">
                <Dropdown.Header>
                  {user.username} · {isAdmin ? 'Administrator' : 'Operator'}
                </Dropdown.Header>
                <Dropdown.ItemText>
                  <Form.Check
                    type="switch"
                    id="expert-mode"
                    label="Expert details"
                    checked={expert}
                    onChange={(e) => setExpert(e.target.checked)}
                  />
                  <div className="form-text mt-0">
                    Show raw variables, commit hashes and other technical details.
                  </div>
                </Dropdown.ItemText>
                <Dropdown.Divider />
                <Dropdown.Header>Theme</Dropdown.Header>
                {THEMES.map(({ value, label, icon: ThemeIcon }) => (
                  <Dropdown.Item
                    key={value}
                    as="button"
                    active={theme.preference === value}
                    onClick={() => theme.setPreference(value)}
                    className="d-flex align-items-center gap-2"
                  >
                    <ThemeIcon size={14} aria-hidden />
                    {label}
                  </Dropdown.Item>
                ))}
                <Dropdown.Divider />
                <Dropdown.Item
                  as="button"
                  onClick={() => logout.mutate()}
                  disabled={logout.isPending}
                  className="d-flex align-items-center gap-2"
                >
                  <BoxArrowRight size={14} aria-hidden />
                  Sign out
                </Dropdown.Item>
              </Dropdown.Menu>
            </Dropdown>
          </div>
        </Offcanvas.Body>
      </Navbar.Offcanvas>
    </Navbar>
  )
}
