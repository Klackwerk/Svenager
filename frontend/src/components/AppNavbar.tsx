import Button from 'react-bootstrap/Button'
import Container from 'react-bootstrap/Container'
import Dropdown from 'react-bootstrap/Dropdown'
import Form from 'react-bootstrap/Form'
import Nav from 'react-bootstrap/Nav'
import Navbar from 'react-bootstrap/Navbar'
import Offcanvas from 'react-bootstrap/Offcanvas'
import { NavLink } from 'react-router-dom'
import { useLogout } from '../api/hooks'
import type { AuthUser } from '../api/types'
import { useExpertMode } from '../lib/expertMode'
import type { ThemePreference } from '../lib/theme'
import GlobalSearch from './GlobalSearch'

const THEME_LABELS: Record<ThemePreference, string> = {
  light: 'Light',
  dark: 'Dark',
  system: 'System',
}

const links: Array<{ to: string; label: string; adminOnly?: boolean }> = [
  { to: '/', label: 'Dashboard' },
  { to: '/devices', label: 'Devices' },
  { to: '/groups', label: 'Groups' },
  { to: '/sources', label: 'Ansible sources' },
  { to: '/jobs', label: 'Jobs' },
  { to: '/enrollment', label: 'Enrollment' },
  { to: '/alerting', label: 'Alerting', adminOnly: true },
  { to: '/audit', label: 'Audit', adminOnly: true },
  { to: '/users', label: 'Users', adminOnly: true },
]

interface AppNavbarProps {
  user: AuthUser
  theme: { preference: ThemePreference; setPreference: (preference: ThemePreference) => void }
}

// Collapses into an offcanvas drawer below the lg breakpoint so the full
// navigation stays reachable on phones.
export default function AppNavbar({ user, theme }: AppNavbarProps) {
  const logout = useLogout()
  const { expert, setExpert } = useExpertMode()

  return (
    <Navbar expand="lg" bg="dark" data-bs-theme="dark" sticky="top">
      <Container fluid>
        <Navbar.Brand as={NavLink} to="/">
          Svenager
        </Navbar.Brand>
        <Navbar.Toggle aria-controls="main-nav" />
        <Navbar.Offcanvas id="main-nav" placement="end" aria-labelledby="main-nav-label">
          <Offcanvas.Header closeButton>
            <Offcanvas.Title id="main-nav-label">Svenager</Offcanvas.Title>
          </Offcanvas.Header>
          <Offcanvas.Body>
            <Nav className="me-auto">
              {links
                .filter(({ adminOnly }) => !adminOnly || user.roles.includes('ROLE_ADMIN'))
                .map(({ to, label }) => (
                  <Nav.Link key={to} as={NavLink} to={to} end={to === '/'}>
                    {label}
                  </Nav.Link>
                ))}
            </Nav>
            <div className="d-flex align-items-center gap-3 mt-3 mt-lg-0 flex-wrap">
              <GlobalSearch />
              <Form.Check
                type="switch"
                id="expert-mode"
                label="Expert"
                title="Show raw variables, commit hashes and other technical details"
                className="text-light"
                checked={expert}
                onChange={(e) => setExpert(e.target.checked)}
              />
              <Dropdown align="end">
                <Dropdown.Toggle size="sm" variant="outline-light" id="theme-toggle">
                  Theme: {THEME_LABELS[theme.preference]}
                </Dropdown.Toggle>
                <Dropdown.Menu>
                  {(Object.keys(THEME_LABELS) as ThemePreference[]).map((option) => (
                    <Dropdown.Item
                      key={option}
                      active={theme.preference === option}
                      onClick={() => theme.setPreference(option)}
                    >
                      {THEME_LABELS[option]}
                    </Dropdown.Item>
                  ))}
                </Dropdown.Menu>
              </Dropdown>
              <span className="navbar-text">{user.username}</span>
              <Button size="sm" variant="outline-light" onClick={() => logout.mutate()} disabled={logout.isPending}>
                Sign out
              </Button>
            </div>
          </Offcanvas.Body>
        </Navbar.Offcanvas>
      </Container>
    </Navbar>
  )
}
