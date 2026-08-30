export interface AuthUser {
  username: string
  roles: string[]
}

export interface UserInfo {
  id: string
  username: string
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER'
  enabled: boolean
  /** LOCAL = password account; OIDC = managed by the identity provider. */
  source: 'LOCAL' | 'OIDC'
  /** false = access limited to the groups in `scopes`. */
  allGroups: boolean
  scopes: string[]
  createdAt: string | null
}

export interface SearchResults {
  devices: Array<{ id: string; hostname: string; online: boolean; status: 'ACTIVE' | 'DISABLED' }>
  groups: GroupRef[]
  roles: Array<{ id: string; name: string; displayName: string; repository: string; missing: boolean }>
  jobs: Array<{ id: string; hostname: string; status: string; type: string }>
}

export interface LoginOptions {
  sso: boolean
  ssoUrl: string | null
  ssoLabel: string | null
}

export interface SsoMappingInfo {
  id: string
  idpGroup: string
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER' | null
  deviceGroupId: string | null
  deviceGroupName: string | null
  createdAt: string | null
}

export interface DeviceSummary {
  id: string
  hostname: string
  status: 'ACTIVE' | 'DISABLED'
  online: boolean
  agentVersion: string | null
  lastContactAt: string | null
  /** Address the agent reports for itself. */
  ip: string | null
  /** Address the server saw the last check-in from (NAT/proxy). */
  lastIp: string | null
  lastJobAt: string | null
  enrolledAt: string | null
  groups: GroupRef[]
}

export interface DevicePage {
  items: DeviceSummary[]
  /** Devices matching the current filter. */
  total: number
  offset: number
  max: number
  /** Online devices across the whole fleet. */
  online: number
  /** All devices across the whole fleet. */
  all: number
}

export interface DeviceDetail extends DeviceSummary {
  facts: Record<string, string>
  variables: VariableEntry[]
}

export interface GroupRef {
  id: string
  name: string
}

export interface GroupSummary extends GroupRef {
  description: string | null
  /** Agent check-in interval override in seconds; null uses the default. */
  pollIntervalSeconds: number | null
  /** Silence before an offline alert, in seconds; null uses the default. */
  offlineAlertSeconds: number | null
  deviceCount: number
  roleCount: number
}

export interface GroupDetail extends GroupSummary {
  devices: Array<{ id: string; hostname: string; online: boolean }>
  roles: RoleAssignment[]
  variables: VariableEntry[]
}

export interface RoleAssignment {
  id: string
  roleId: string
  roleName: string
  displayName: string
  repository: string
  missing: boolean
  position: number
  enabled: boolean
}

export interface VariableEntry {
  name: string
  /** null for secrets (value is never sent back to the browser) */
  value: unknown
  secret: boolean
}

export interface ArgumentSpecOption {
  type?: string
  required?: boolean
  description?: string | string[]
  default?: unknown
  choices?: unknown[]
}

export interface RoleInfo {
  id: string
  name: string
  displayName: string
  description: string | null
  userAssignable: boolean
  missing: boolean
  repositoryId: string
  repository: string
  argumentSpec: Record<string, ArgumentSpecOption>
  defaults: Record<string, unknown>
}

export type RepoAuthType = 'NONE' | 'SSH_KEY' | 'HTTPS_TOKEN'

export interface RepositorySummary {
  id: string
  name: string
  gitUrl: string
  branch: string
  authType: RepoAuthType
  /** HTTPS_TOKEN only; the token itself is never sent back */
  authUsername: string | null
  hasCredentials: boolean
  deployKeyPublic: string | null
  syncStatus: 'NEVER' | 'SYNCING' | 'OK' | 'ERROR'
  syncError: string | null
  lastCommit: string | null
  lastSyncedAt: string | null
  roleCount: number
}

/** Create/update body for a repository; omitted secrets keep their value. */
export interface RepositoryInput {
  name?: string
  gitUrl?: string
  branch?: string
  authType?: RepoAuthType
  authUsername?: string
  authSecret?: string
  generateDeployKey?: boolean
  sshPrivateKey?: string
}

export interface EnrollmentToken {
  id: string
  label: string
  maxUses: number
  usedCount: number
  expiresAt: string | null
  revoked: boolean
  usable: boolean
  createdBy: string | null
  createdAt: string | null
  /** Devices enrolled with this token join these groups immediately. */
  targetGroups: GroupRef[]
}

export interface CreatedEnrollmentToken extends EnrollmentToken {
  /** The raw token — returned exactly once at creation time. */
  token: string
  /** Ready-to-paste one-liner: installs the agent from this instance and enrolls. */
  installCommand: string
}

export interface EnrollmentRequestInfo {
  id: string
  requestId: string
  hostname: string | null
  facts: Record<string, string>
  status: 'PENDING' | 'APPROVED' | 'DENIED' | 'COMPLETED'
  requestedAt: string | null
  lastSeenAt: string | null
  decidedBy: string | null
  decidedAt: string | null
  deviceId: string | null
}

export interface JobSummary {
  id: string
  deviceId: string
  hostname: string
  type: string
  status: 'PENDING' | 'DELIVERED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED'
  exitCode: number | null
  error: string | null
  triggeredBy: string | null
  /** 1 for operator/drift jobs; auto-retries count up to maxAttempts. */
  attempt: number
  maxAttempts: number
  /** Failed apply that used up its auto-retries — needs Re-run/Apply. */
  retriesExhausted: boolean
  /** Not delivered before this time; null = immediately. */
  runAfter: string | null
  queuedAt: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface JobBatchInfo {
  id: string
  groupId: string | null
  groupName: string | null
  triggeredBy: string | null
  /** null = plain fan-out; CANARY = one device first; FULL = continued. */
  stage: 'CANARY' | 'FULL' | null
  createdAt: string | null
  total: number
  counts: Partial<Record<JobSummary['status'], number>>
  done: boolean
  jobs: JobSummary[]
}

export interface JobPage {
  items: JobSummary[]
  total: number
  offset: number
  max: number
}

export interface JobDetail extends JobSummary {
  /** APPLY_CONFIG carries plays/vars; AGENT_UPDATE only a version; PING nothing. */
  payload: {
    timeoutSeconds?: number
    plays?: Array<{ repoId: number; repoName: string; commit: string; roles: string[] }>
    extraVars?: Record<string, unknown>
    secretVars?: string[]
    version?: string
  } | null
  log: string
}

export interface RemoteSessionInfo {
  sessionId: string
  deviceId: string
  hostname: string
  status: 'PENDING' | 'AGENT_CONNECTED' | 'ACTIVE' | 'CLOSED'
  kind: 'VNC' | 'SHELL'
  requestedBy: string | null
  createdAt: string | null
  expiresAt: string | null
  agentConnectedAt: string | null
  viewerConnectedAt: string | null
  closedAt: string | null
  closeReason: string | null
  wsPath: string
}

export interface AuditEntry {
  id: number
  actor: string
  action: string
  entityType: string | null
  entityId: string | null
  summary: string | null
  ip: string | null
  at: string | null
}

export interface AuditPage {
  items: AuditEntry[]
  total: number
  offset: number
  max: number
}

export interface NotificationChannelInfo {
  id: string
  name: string
  type: 'EMAIL' | 'WEBHOOK'
  target: string
  enabled: boolean
  createdAt: string | null
}

export interface DashboardGroup {
  id: string
  name: string
  description: string | null
  deviceCount: number
  onlineCount: number
  roleCount: number
  lastContactAt: string | null
  lastJobAt: string | null
  jobs: JobStats
}

export interface JobStats {
  succeeded: number
  failed: number
  active: number
}

export interface DashboardOverview {
  devices: { total: number; online: number; offline: number; ungrouped: number }
  jobs: JobStats
  repos: { total: number; errors: number; neverSynced: number }
  groups: DashboardGroup[]
  windowDays: number
}
