import Alert from 'react-bootstrap/Alert'
import Card from 'react-bootstrap/Card'
import Spinner from 'react-bootstrap/Spinner'
import { Link, Navigate, Route, Routes, useParams } from 'react-router-dom'
import { useMe } from './api/hooks'
import { ExpertModeProvider } from './lib/expertMode'
import { useTheme } from './lib/theme'
import AppSidebar from './components/AppSidebar'
import JobModal from './components/JobModal'
import Dashboard from './pages/Dashboard'
import Devices from './pages/Devices'
import DeviceDetail from './pages/DeviceDetail'
import Groups from './pages/Groups'
import GroupDetail from './pages/GroupDetail'
import AnsibleSources from './pages/AnsibleSources'
import Jobs from './pages/Jobs'
import BatchDetail from './pages/BatchDetail'
import Enrollment from './pages/Enrollment'
import Alerting from './pages/Alerting'
import Audit from './pages/Audit'
import Login from './pages/Login'
import DeviceShell from './pages/DeviceShell'
import RemoteView from './pages/RemoteView'
import Users from './pages/Users'

function ExplainerCard({ title, text }: { title: string; text: string }) {
  return (
    <Card className="mx-auto mt-5" style={{ maxWidth: '28rem' }}>
      <Card.Body className="text-center">
        <Card.Title className="h5">{title}</Card.Title>
        <Card.Text className="text-secondary">{text}</Card.Text>
        <Link to="/">Go to the dashboard</Link>
      </Card.Body>
    </Card>
  )
}

// Old bookmarks and notifications link to /jobs/:id; the job now opens
// as a dialog over the jobs list.
function LegacyJobRedirect() {
  const { id } = useParams()
  return <Navigate to={{ pathname: '/jobs', search: `?job=${encodeURIComponent(id ?? '')}` }} replace />
}

function NotFound() {
  return <ExplainerCard title="Page not found" text="This page does not exist — maybe the link is outdated." />
}

function AdminRequired() {
  return (
    <ExplainerCard
      title="Administrator rights required"
      text="Only administrators can manage users. Ask an administrator if you need access."
    />
  )
}

export default function App() {
  const { data: user, isLoading, isError } = useMe()
  const theme = useTheme()

  if (isLoading) {
    return (
      <div className="d-flex justify-content-center align-items-center min-vh-100">
        <Spinner role="status" aria-label="Loading" />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="container py-5">
        <Alert variant="danger">The server could not be reached. Please try again in a moment.</Alert>
      </div>
    )
  }

  if (!user) {
    return <Login />
  }

  return (
    <ExpertModeProvider>
      <div className="app-shell">
        <AppSidebar user={user} theme={theme} />
        <main className="app-main container-fluid py-3 px-3 px-md-4">
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/devices" element={<Devices />} />
            <Route path="/devices/:id" element={<DeviceDetail />} />
            <Route path="/devices/:id/remote" element={<RemoteView />} />
            <Route path="/devices/:id/shell" element={<DeviceShell />} />
            <Route path="/groups" element={<Groups />} />
            <Route path="/groups/:id" element={<GroupDetail />} />
            <Route path="/sources" element={<AnsibleSources />} />
            <Route path="/jobs" element={<Jobs />} />
            <Route path="/jobs/:id" element={<LegacyJobRedirect />} />
            <Route path="/batches/:id" element={<BatchDetail />} />
            <Route path="/enrollment" element={<Enrollment />} />
            <Route path="/users" element={user.roles.includes('ROLE_ADMIN') ? <Users /> : <AdminRequired />} />
            <Route
              path="/alerting"
              element={user.roles.includes('ROLE_ADMIN') ? <Alerting /> : <AdminRequired />}
            />
            <Route path="/audit" element={user.roles.includes('ROLE_ADMIN') ? <Audit /> : <AdminRequired />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
        <JobModal />
      </div>
    </ExpertModeProvider>
  )
}
