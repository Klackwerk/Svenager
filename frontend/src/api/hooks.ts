import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from './client'
import type {
  AuditPage,
  AuthUser,
  CreatedEnrollmentToken,
  DashboardOverview,
  DeviceDetail,
  DevicePage,
  DeviceSummary,
  EnrollmentRequestInfo,
  EnrollmentToken,
  GroupDetail,
  GroupRef,
  GroupSummary,
  JobBatchInfo,
  JobDetail,
  JobPage,
  JobSummary,
  LoginOptions,
  NotificationChannelInfo,
  SsoMappingInfo,
  RemoteSessionInfo,
  RepositoryInput,
  RepositorySummary,
  SearchResults,
  RoleAssignment,
  RoleInfo,
  UserInfo,
  VariableEntry,
} from './types'

export function useMe() {
  return useQuery<AuthUser | null>({
    queryKey: ['me'],
    queryFn: async () => {
      try {
        return await api<AuthUser>('/api/v1/auth/me')
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null
        throw error
      }
    },
    staleTime: 5 * 60_000,
    retry: false,
  })
}

export function useLoginOptions() {
  return useQuery<LoginOptions>({
    queryKey: ['login-options'],
    queryFn: () => api<LoginOptions>('/api/v1/auth/login-options'),
    staleTime: Infinity,
    retry: false,
  })
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (credentials: { username: string; password: string }) =>
      api<AuthUser>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(credentials) }),
    meta: { silentError: true },
    onSuccess: (user) => queryClient.setQueryData(['me'], user),
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api<void>('/api/v1/auth/logout', { method: 'POST' }),
    meta: { errorMessage: 'Sign-out failed.' },
    onSuccess: () => queryClient.setQueryData(['me'], null),
  })
}

export interface DeviceFilters {
  /** Matches hostname, device id and agent version. */
  q?: string
  /** online | offline | disabled */
  statuses?: string[]
  /** Group ids and/or 'none' for ungrouped devices. */
  groupIds?: string[]
}

export function useDevices(
  filters: DeviceFilters = {},
  offset = 0,
  max = 50,
  sort = 'hostname',
  order: 'asc' | 'desc' = 'asc',
) {
  return useQuery<DevicePage>({
    queryKey: ['devices', filters, offset, max, sort, order],
    queryFn: () => {
      const params = new URLSearchParams({
        offset: String(offset),
        max: String(max),
        sort,
        order,
      })
      if (filters.q) params.set('q', filters.q)
      if (filters.statuses?.length) params.set('status', filters.statuses.join(','))
      if (filters.groupIds?.length) params.set('groupId', filters.groupIds.join(','))
      return api<DevicePage>(`/api/v1/devices?${params}`)
    },
    refetchInterval: 10_000,
    // Keep the previous page visible while the next one loads.
    placeholderData: (previous) => previous,
  })
}

export function useDevice(id: string | null) {
  return useQuery<DeviceDetail>({
    queryKey: ['devices', id],
    queryFn: () => api<DeviceDetail>(`/api/v1/devices/${id}`),
    enabled: id != null,
    // Keep the Online badge and facts fresh while the page is open.
    refetchInterval: 10_000,
  })
}

export function useRenameDevice(deviceId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (hostname: string) =>
      api<DeviceSummary>(`/api/v1/devices/${deviceId}`, { method: 'PUT', body: JSON.stringify({ hostname }) }),
    meta: { silentError: true },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['devices'] }),
  })
}

export function useSetDeviceStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: 'ACTIVE' | 'DISABLED' }) =>
      api<DeviceSummary>(`/api/v1/devices/${id}`, { method: 'PUT', body: JSON.stringify({ status }) }),
    meta: { errorMessage: 'The device status could not be changed.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['devices'] }),
  })
}

export function useBulkSetStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ status, deviceIds }: { status: 'ACTIVE' | 'DISABLED'; deviceIds: string[] }) => {
      for (const id of deviceIds) {
        await api<DeviceSummary>(`/api/v1/devices/${id}`, { method: 'PUT', body: JSON.stringify({ status }) })
      }
    },
    meta: { errorMessage: 'Changing the device status failed part-way — check the list.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['devices'] }),
  })
}

export function useDeleteDevice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/v1/devices/${id}`, { method: 'DELETE' }),
    meta: { errorMessage: 'The device could not be removed.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['devices'] }),
  })
}

// Bulk actions run sequentially so a failure stops early instead of
// hammering the server with doomed requests.

export function useBulkAddToGroup() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ groupId, deviceIds }: { groupId: string; deviceIds: string[] }) => {
      for (const deviceId of deviceIds) {
        await api<void>(`/api/v1/groups/${groupId}/devices`, {
          method: 'POST',
          body: JSON.stringify({ deviceId }),
        })
      }
    },
    meta: { errorMessage: 'Adding the devices to the group failed part-way — check the group.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['devices'] })
      queryClient.invalidateQueries({ queryKey: ['groups'] })
    },
  })
}

export function useBulkRemoveFromGroup() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ groupId, deviceIds }: { groupId: string; deviceIds: string[] }) => {
      for (const deviceId of deviceIds) {
        await api<void>(`/api/v1/groups/${groupId}/devices/${deviceId}`, { method: 'DELETE' })
      }
    },
    meta: { errorMessage: 'Removing the devices from the group failed part-way — check the group.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['devices'] })
      queryClient.invalidateQueries({ queryKey: ['groups'] })
    },
  })
}

export function useBulkApply() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (deviceIds: string[]) => {
      for (const deviceId of deviceIds) {
        await api<JobSummary>(`/api/v1/devices/${deviceId}/apply`, { method: 'POST' })
      }
    },
    meta: { errorMessage: 'Queuing the applies failed part-way — check the Jobs page.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
}

export function useEnrollmentTokens() {
  return useQuery<EnrollmentToken[]>({
    queryKey: ['enrollment-tokens'],
    queryFn: () => api<EnrollmentToken[]>('/api/v1/enrollment-tokens'),
  })
}

export function useCreateEnrollmentToken() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: {
      label: string
      maxUses: number
      expiresInHours: number | null
      targetGroupIds?: string[]
    }) =>
      api<CreatedEnrollmentToken>('/api/v1/enrollment-tokens', { method: 'POST', body: JSON.stringify(input) }),
    meta: { silentError: true },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['enrollment-tokens'] }),
  })
}

export function useRevokeEnrollmentToken() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/v1/enrollment-tokens/${id}`, { method: 'DELETE' }),
    meta: { errorMessage: 'The token could not be revoked.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['enrollment-tokens'] }),
  })
}

export function useEnrollmentRequests() {
  return useQuery<EnrollmentRequestInfo[]>({
    queryKey: ['enrollment-requests'],
    queryFn: () => api<EnrollmentRequestInfo[]>('/api/v1/enrollment-requests'),
    refetchInterval: 10_000,
  })
}

export function useDecideEnrollmentRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, decision }: { id: string; decision: 'approve' | 'deny' }) =>
      api<EnrollmentRequestInfo>(`/api/v1/enrollment-requests/${id}/${decision}`, { method: 'POST' }),
    meta: { errorMessage: 'The enrollment decision could not be saved.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['enrollment-requests'] })
      queryClient.invalidateQueries({ queryKey: ['devices'] })
    },
  })
}

// --- users --------------------------------------------------------------------

export function useUsers() {
  return useQuery<UserInfo[]>({
    queryKey: ['users'],
    queryFn: () => api<UserInfo[]>('/api/v1/users'),
  })
}

export function useCreateUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { username: string; password: string; role: UserInfo['role'] }) =>
      api<UserInfo>('/api/v1/users', { method: 'POST', body: JSON.stringify(input) }),
    meta: { silentError: true },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  })
}

export function useUpdateUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...changes }: { id: string; role?: UserInfo['role']; enabled?: boolean; password?: string }) =>
      api<UserInfo>(`/api/v1/users/${id}`, { method: 'PUT', body: JSON.stringify(changes) }),
    meta: { errorMessage: 'The user change could not be saved.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  })
}

// --- sso mappings ---------------------------------------------------------------

export function useSsoMappings() {
  return useQuery<SsoMappingInfo[]>({
    queryKey: ['sso-mappings'],
    queryFn: () => api<SsoMappingInfo[]>('/api/v1/sso-mappings'),
  })
}

export function useCreateSsoMapping() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { idpGroup: string; role?: string; deviceGroupId?: string }) =>
      api<SsoMappingInfo>('/api/v1/sso-mappings', { method: 'POST', body: JSON.stringify(input) }),
    meta: { silentError: true },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sso-mappings'] }),
  })
}

export function useDeleteSsoMapping() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/v1/sso-mappings/${id}`, { method: 'DELETE' }),
    meta: { errorMessage: 'The mapping could not be removed.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sso-mappings'] }),
  })
}

// --- groups -----------------------------------------------------------------

export function useGroups() {
  return useQuery<GroupSummary[]>({
    queryKey: ['groups'],
    queryFn: () => api<GroupSummary[]>('/api/v1/groups'),
  })
}

export function useGroup(id: string) {
  return useQuery<GroupDetail>({
    queryKey: ['groups', id],
    queryFn: () => api<GroupDetail>(`/api/v1/groups/${id}`),
    refetchInterval: 15_000,
  })
}

function useGroupMutation<TInput, TOutput>(
  fn: (input: TInput) => Promise<TOutput>,
  meta?: { errorMessage?: string; successMessage?: string; silentError?: boolean },
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: fn,
    meta,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] })
      queryClient.invalidateQueries({ queryKey: ['devices'] })
    },
  })
}

export function useCreateGroup() {
  return useGroupMutation(
    (input: { name: string; description: string }) =>
      api<GroupSummary>('/api/v1/groups', { method: 'POST', body: JSON.stringify(input) }),
    { silentError: true },
  )
}

export function useUpdateGroup(groupId: string) {
  return useGroupMutation(
    (changes: {
      name?: string
      description?: string
      pollIntervalSeconds?: number | null
      offlineAlertSeconds?: number | null
    }) =>
      api<GroupSummary>(`/api/v1/groups/${groupId}`, { method: 'PUT', body: JSON.stringify(changes) }),
    { errorMessage: 'The group settings could not be saved.', successMessage: 'Group settings saved.' },
  )
}

export function useDeleteGroup() {
  return useGroupMutation((id: string) => api<void>(`/api/v1/groups/${id}`, { method: 'DELETE' }), {
    errorMessage: 'The group could not be deleted.',
  })
}

export function useAddGroupDevice(groupId: string) {
  return useGroupMutation(
    (deviceId: string) =>
      api<void>(`/api/v1/groups/${groupId}/devices`, { method: 'POST', body: JSON.stringify({ deviceId }) }),
    { errorMessage: 'The device could not be added to the group.' },
  )
}

export function useRemoveGroupDevice(groupId: string) {
  return useGroupMutation(
    (deviceId: string) => api<void>(`/api/v1/groups/${groupId}/devices/${deviceId}`, { method: 'DELETE' }),
    { errorMessage: 'The device could not be removed from the group.' },
  )
}

export function useAddGroupRole(groupId: string) {
  return useGroupMutation(
    (roleId: string) =>
      api<RoleAssignment>(`/api/v1/groups/${groupId}/roles`, { method: 'POST', body: JSON.stringify({ roleId }) }),
    { errorMessage: 'The role could not be assigned.' },
  )
}

export function useRemoveGroupRole(groupId: string) {
  return useGroupMutation(
    (assignmentId: string) => api<void>(`/api/v1/groups/${groupId}/roles/${assignmentId}`, { method: 'DELETE' }),
    { errorMessage: 'The role could not be removed.' },
  )
}

export function useReorderGroupRoles(groupId: string) {
  return useGroupMutation(
    (assignmentIds: string[]) =>
      api<RoleAssignment[]>(`/api/v1/groups/${groupId}/roles/order`, {
        method: 'PUT',
        body: JSON.stringify({ assignmentIds }),
      }),
    { errorMessage: 'The role order could not be saved.' },
  )
}

export function useReplaceGroupVariables(groupId: string) {
  return useGroupMutation(
    (variables: VariableEntry[]) =>
      api<VariableEntry[]>(`/api/v1/groups/${groupId}/variables`, { method: 'PUT', body: JSON.stringify(variables) }),
    { silentError: true },
  )
}

export function useSetDeviceGroups(deviceId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (groupIds: string[]) =>
      api<GroupRef[]>(`/api/v1/devices/${deviceId}/groups`, { method: 'PUT', body: JSON.stringify({ groupIds }) }),
    meta: { errorMessage: 'The group memberships could not be saved.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['devices'] })
      queryClient.invalidateQueries({ queryKey: ['groups'] })
    },
  })
}

export function useReplaceDeviceVariables(deviceId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (variables: VariableEntry[]) =>
      api<VariableEntry[]>(`/api/v1/devices/${deviceId}/variables`, {
        method: 'PUT',
        body: JSON.stringify(variables),
      }),
    meta: { silentError: true },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['devices'] }),
  })
}

// --- ansible repositories -----------------------------------------------------

export function useRepositories() {
  return useQuery<RepositorySummary[]>({
    queryKey: ['repositories'],
    queryFn: () => api<RepositorySummary[]>('/api/v1/repositories'),
  })
}

export function useCreateRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: RepositoryInput) =>
      api<RepositorySummary>('/api/v1/repositories', { method: 'POST', body: JSON.stringify(input) }),
    meta: { silentError: true },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['repositories'] }),
  })
}

export function useUpdateRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...changes }: RepositoryInput & { id: string }) =>
      api<RepositorySummary>(`/api/v1/repositories/${id}`, { method: 'PUT', body: JSON.stringify(changes) }),
    meta: { silentError: true },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['repositories'] }),
  })
}

export function useDeleteRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/v1/repositories/${id}`, { method: 'DELETE' }),
    meta: { errorMessage: 'The repository could not be removed.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repositories'] })
      queryClient.invalidateQueries({ queryKey: ['roles'] })
    },
  })
}

export function useSyncRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<RepositorySummary>(`/api/v1/repositories/${id}/sync`, { method: 'POST' }),
    meta: { errorMessage: 'The sync could not be started.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repositories'] })
      queryClient.invalidateQueries({ queryKey: ['roles'] })
    },
  })
}

export function useRepositoryRoles(id: string) {
  return useQuery<RoleInfo[]>({
    queryKey: ['repositories', id, 'roles'],
    queryFn: () => api<RoleInfo[]>(`/api/v1/repositories/${id}/roles`),
  })
}

export function useGroupEffectiveRoles(groupId: string) {
  return useQuery<RoleInfo[]>({
    queryKey: ['groups', groupId, 'effective-roles'],
    queryFn: () => api<RoleInfo[]>(`/api/v1/groups/${groupId}/effective-roles`),
  })
}

export function useDeviceEffectiveRoles(deviceId: string | null) {
  return useQuery<RoleInfo[]>({
    queryKey: ['devices', deviceId, 'effective-roles'],
    queryFn: () => api<RoleInfo[]>(`/api/v1/devices/${deviceId}/effective-roles`),
    enabled: deviceId != null,
  })
}

export function useAssignableRoles() {
  return useQuery<RoleInfo[]>({
    queryKey: ['roles'],
    queryFn: () => api<RoleInfo[]>('/api/v1/roles'),
  })
}

// --- jobs ---------------------------------------------------------------------

export interface JobFilters {
  deviceId?: string
  /** Raw JobStatus names; combined server-side with OR. */
  statuses?: string[]
  type?: string
  groupId?: string
  /** Matches device hostname and triggeredBy. */
  q?: string
  /** yyyy-MM-dd (inclusive). */
  from?: string
  to?: string
}

export function useJobs(filters: JobFilters = {}, offset = 0, max = 50) {
  return useQuery<JobPage>({
    queryKey: ['jobs', filters, offset, max],
    queryFn: () => {
      const params = new URLSearchParams({ offset: String(offset), max: String(max) })
      if (filters.deviceId) params.set('deviceId', filters.deviceId)
      if (filters.statuses?.length) params.set('status', filters.statuses.join(','))
      if (filters.type) params.set('type', filters.type)
      if (filters.groupId != null) params.set('groupId', filters.groupId)
      if (filters.q) params.set('q', filters.q)
      if (filters.from) params.set('from', filters.from)
      if (filters.to) params.set('to', filters.to)
      return api<JobPage>(`/api/v1/jobs?${params}`)
    },
    refetchInterval: 5_000,
    // Keep the previous page visible while the next one loads.
    placeholderData: (previous) => previous,
  })
}

export function useJob(id: string) {
  return useQuery<JobDetail>({
    queryKey: ['jobs', 'detail', id],
    queryFn: () => api<JobDetail>(`/api/v1/jobs/${id}`),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(status) ? false : 2_000
    },
  })
}

export function useCancelJob() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<JobSummary>(`/api/v1/jobs/${id}/cancel`, { method: 'POST' }),
    meta: { errorMessage: 'The job could not be cancelled.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
}

export function useRerunJob() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<JobSummary>(`/api/v1/jobs/${id}/rerun`, { method: 'POST' }),
    meta: { errorMessage: 'The re-run could not be queued.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
}

export function useApplyDevice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ deviceId, runAfter }: { deviceId: string; runAfter?: string | null }) =>
      api<JobSummary>(`/api/v1/devices/${deviceId}/apply`, {
        method: 'POST',
        body: JSON.stringify({ runAfter: runAfter ?? null }),
      }),
    meta: { errorMessage: 'The configuration apply could not be queued.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
}

export function useUpdateAgent() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ deviceId, version }: { deviceId: string; version?: string }) =>
      api<JobSummary>(`/api/v1/devices/${deviceId}/update-agent`, {
        method: 'POST',
        body: JSON.stringify({ version: version ?? null }),
      }),
    meta: { errorMessage: 'The agent update could not be queued.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
}

export function useRebootDevice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (deviceId: string) =>
      api<JobSummary>(`/api/v1/devices/${deviceId}/reboot`, { method: 'POST' }),
    meta: { errorMessage: 'The reboot could not be queued.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
}

export function usePreviewDevice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (deviceId: string) => api<JobSummary>(`/api/v1/devices/${deviceId}/preview`, { method: 'POST' }),
    meta: { errorMessage: 'The preview could not be queued.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  })
}

export function useApplyGroup() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ groupId, canary, runAfter }: { groupId: string; canary?: boolean; runAfter?: string | null }) =>
      api<JobBatchInfo>(`/api/v1/groups/${groupId}/apply`, {
        method: 'POST',
        body: JSON.stringify({ canary: canary === true, runAfter: runAfter ?? null }),
      }),
    meta: { errorMessage: 'The configuration apply could not be queued.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] })
      queryClient.invalidateQueries({ queryKey: ['batches'] })
    },
  })
}

// --- batches --------------------------------------------------------------------

/** Polls the batch roll-up until every job in it is terminal. */
export function useBatch(id: string) {
  return useQuery<JobBatchInfo>({
    queryKey: ['batches', id],
    queryFn: () => api<JobBatchInfo>(`/api/v1/batches/${id}`),
    refetchInterval: (query) => (query.state.data?.done ? false : 3_000),
  })
}

export function useGroupBatches(groupId: string) {
  return useQuery<JobBatchInfo[]>({
    queryKey: ['batches', 'group', groupId],
    queryFn: () => api<JobBatchInfo[]>(`/api/v1/groups/${groupId}/batches`),
    refetchInterval: 15_000,
  })
}

export function useContinueRollout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (batchId: string) => api<JobBatchInfo>(`/api/v1/batches/${batchId}/continue`, { method: 'POST' }),
    meta: { errorMessage: 'The rollout could not be continued.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] })
      queryClient.invalidateQueries({ queryKey: ['batches'] })
    },
  })
}

export function useRetryBatch() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (batchId: string) => api<JobBatchInfo>(`/api/v1/batches/${batchId}/retry`, { method: 'POST' }),
    meta: { errorMessage: 'The retry could not be queued.' },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] })
      queryClient.invalidateQueries({ queryKey: ['batches'] })
    },
  })
}

// --- remote view ---------------------------------------------------------------

/**
 * Opens (or reuses) the device's remote session. The POST is get-or-create,
 * so a query models it robustly — mutation callbacks are lost on StrictMode
 * remounts, a query result is not.
 */
export function useOpenRemoteSession(deviceId: string | null) {
  return useQuery<RemoteSessionInfo>({
    queryKey: ['remote-sessions', 'open', deviceId],
    queryFn: () =>
      api<RemoteSessionInfo>(`/api/v1/devices/${deviceId}/remote-session`, { method: 'POST' }),
    enabled: deviceId != null,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  })
}

export function useOpenShellSession(deviceId: string | null) {
  return useQuery<RemoteSessionInfo>({
    queryKey: ['remote-sessions', 'shell', deviceId],
    queryFn: () =>
      api<RemoteSessionInfo>(`/api/v1/devices/${deviceId}/shell-session`, { method: 'POST' }),
    enabled: deviceId != null,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  })
}

/** Polls while the session is being established or live; stops once closed. */
export function useRemoteSession(sessionId: string | null) {
  return useQuery<RemoteSessionInfo>({
    queryKey: ['remote-sessions', sessionId],
    queryFn: () => api<RemoteSessionInfo>(`/api/v1/remote-sessions/${sessionId}`),
    enabled: sessionId != null,
    refetchInterval: (query) => (query.state.data?.status === 'CLOSED' ? false : 2_000),
  })
}

export function useCloseRemoteSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (sessionId: string) =>
      api<RemoteSessionInfo>(`/api/v1/remote-sessions/${sessionId}`, { method: 'DELETE' }),
    meta: { errorMessage: 'The remote session could not be ended.' },
    // Reflect the closed session immediately, but do NOT refetch the
    // get-or-create open/shell queries — that would POST a brand-new session
    // the instant the user ends this one. The audit list refreshes on its
    // own interval.
    onSuccess: (closed, sessionId) => {
      queryClient.setQueryData(['remote-sessions', sessionId], closed)
      queryClient.invalidateQueries({ queryKey: ['remote-sessions'], refetchType: 'none' })
    },
  })
}

export function useDeviceRemoteSessions(deviceId: string | null) {
  return useQuery<RemoteSessionInfo[]>({
    queryKey: ['remote-sessions', 'device', deviceId],
    queryFn: () => api<RemoteSessionInfo[]>(`/api/v1/devices/${deviceId}/remote-sessions`),
    enabled: deviceId != null,
    refetchInterval: 15_000,
  })
}

// --- audit trail ------------------------------------------------------------------

export interface AuditFilters {
  /** Matches summary, action, actor and entity id. */
  q?: string
  from?: string
  to?: string
}

export function useAudit(filters: AuditFilters = {}, offset = 0, max = 50) {
  return useQuery<AuditPage>({
    queryKey: ['audit', filters, offset, max],
    queryFn: () => {
      const params = new URLSearchParams({ offset: String(offset), max: String(max) })
      if (filters.q) params.set('q', filters.q)
      if (filters.from) params.set('from', filters.from)
      if (filters.to) params.set('to', filters.to)
      return api<AuditPage>(`/api/v1/audit?${params}`)
    },
    refetchInterval: 15_000,
    placeholderData: (previous) => previous,
  })
}

// --- notification channels -------------------------------------------------------

export function useNotificationChannels() {
  return useQuery<NotificationChannelInfo[]>({
    queryKey: ['notification-channels'],
    queryFn: () => api<NotificationChannelInfo[]>('/api/v1/notification-channels'),
  })
}

export function useCreateNotificationChannel() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; type: NotificationChannelInfo['type']; target: string }) =>
      api<NotificationChannelInfo>('/api/v1/notification-channels', {
        method: 'POST',
        body: JSON.stringify(input),
      }),
    meta: { silentError: true },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notification-channels'] }),
  })
}

export function useUpdateNotificationChannel() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...changes }: { id: string; enabled?: boolean; name?: string; target?: string }) =>
      api<NotificationChannelInfo>(`/api/v1/notification-channels/${id}`, {
        method: 'PUT',
        body: JSON.stringify(changes),
      }),
    meta: { errorMessage: 'The channel could not be updated.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notification-channels'] }),
  })
}

export function useDeleteNotificationChannel() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/v1/notification-channels/${id}`, { method: 'DELETE' }),
    meta: { errorMessage: 'The channel could not be removed.' },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notification-channels'] }),
  })
}

export function useTestNotificationChannel() {
  return useMutation({
    mutationFn: (id: string) =>
      api<{ ok: boolean }>(`/api/v1/notification-channels/${id}/test`, { method: 'POST' }),
    meta: { errorMessage: 'The test alert could not be delivered.' },
  })
}

// --- global search ----------------------------------------------------------------

export function useGlobalSearch(q: string) {
  return useQuery<SearchResults>({
    queryKey: ['search', q],
    queryFn: () => api<SearchResults>(`/api/v1/search?q=${encodeURIComponent(q)}`),
    enabled: q.trim().length >= 2,
    placeholderData: (previous) => previous,
  })
}

// --- dashboard ------------------------------------------------------------------

export function useDashboard() {
  return useQuery<DashboardOverview>({
    queryKey: ['dashboard'],
    queryFn: () => api<DashboardOverview>('/api/v1/dashboard'),
    refetchInterval: 15_000,
  })
}
