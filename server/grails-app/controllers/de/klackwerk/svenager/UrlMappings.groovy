package de.klackwerk.svenager

class UrlMappings {

    static mappings = {
        // Agent API (device-token authenticated; see SecurityConfig)
        post "/api/v1/enroll"(controller: 'enroll', action: 'enroll')
        post "/api/v1/enroll/request"(controller: 'enroll', action: 'register')
        post "/api/v1/agent/checkin"(controller: 'agent', action: 'checkin')
        post "/api/v1/agent/jobs/$id/events"(controller: 'agent', action: 'events')
        get "/api/v1/agent/jobs/$id/bundles/$repoId"(controller: 'agent', action: 'bundle')

        // UI API (session authenticated)
        post "/api/v1/auth/login"(controller: 'auth', action: 'login')
        get "/api/v1/auth/me"(controller: 'auth', action: 'me')
        get "/api/v1/auth/login-options"(controller: 'auth', action: 'loginOptions')
        // /api/v1/auth/sso/** is handled by Spring Security's OAuth2 filters.

        get "/api/v1/sso-mappings"(controller: 'ssoMapping', action: 'index')
        post "/api/v1/sso-mappings"(controller: 'ssoMapping', action: 'save')
        delete "/api/v1/sso-mappings/$id"(controller: 'ssoMapping', action: 'delete')
        // POST /api/v1/auth/logout is handled by Spring Security's logout filter

        get "/api/v1/devices"(controller: 'device', action: 'index')
        get "/api/v1/devices/$id"(controller: 'device', action: 'show')
        put "/api/v1/devices/$id"(controller: 'device', action: 'update')
        delete "/api/v1/devices/$id"(controller: 'device', action: 'delete')
        put "/api/v1/devices/$id/groups"(controller: 'device', action: 'setGroups')
        get "/api/v1/devices/$id/variables"(controller: 'device', action: 'variables')
        put "/api/v1/devices/$id/variables"(controller: 'device', action: 'replaceVariables')
        get "/api/v1/devices/$id/effective-roles"(controller: 'device', action: 'effectiveRoles')

        get "/api/v1/groups"(controller: 'group', action: 'index')
        post "/api/v1/groups"(controller: 'group', action: 'save')
        get "/api/v1/groups/$id"(controller: 'group', action: 'show')
        put "/api/v1/groups/$id"(controller: 'group', action: 'update')
        delete "/api/v1/groups/$id"(controller: 'group', action: 'delete')
        post "/api/v1/groups/$id/devices"(controller: 'group', action: 'addDevice')
        delete "/api/v1/groups/$id/devices/$deviceId"(controller: 'group', action: 'removeDevice')
        post "/api/v1/groups/$id/roles"(controller: 'group', action: 'addRole')
        delete "/api/v1/groups/$id/roles/$assignmentId"(controller: 'group', action: 'removeRole')
        put "/api/v1/groups/$id/roles/order"(controller: 'group', action: 'reorderRoles')
        get "/api/v1/groups/$id/variables"(controller: 'group', action: 'variables')
        put "/api/v1/groups/$id/variables"(controller: 'group', action: 'replaceVariables')
        get "/api/v1/groups/$id/effective-roles"(controller: 'group', action: 'effectiveRoles')

        get "/api/v1/repositories"(controller: 'repository', action: 'index')
        post "/api/v1/repositories"(controller: 'repository', action: 'save')
        put "/api/v1/repositories/$id"(controller: 'repository', action: 'update')
        delete "/api/v1/repositories/$id"(controller: 'repository', action: 'delete')
        post "/api/v1/repositories/$id/sync"(controller: 'repository', action: 'sync')
        get "/api/v1/repositories/$id/roles"(controller: 'repository', action: 'roles')

        get "/api/v1/roles"(controller: 'role', action: 'index')

        post "/api/v1/devices/$id/apply"(controller: 'device', action: 'apply')
        post "/api/v1/devices/$id/preview"(controller: 'device', action: 'preview')
        post "/api/v1/devices/$id/update-agent"(controller: 'device', action: 'updateAgent')
        post "/api/v1/devices/$id/reboot"(controller: 'device', action: 'reboot')
        post "/api/v1/groups/$id/apply"(controller: 'group', action: 'apply')
        get "/api/v1/dashboard"(controller: 'dashboard', action: 'index')
        get "/api/v1/search"(controller: 'search', action: 'index')
        get "/api/v1/inventory"(controller: 'inventory', action: 'index')
        get "/api/v1/jobs"(controller: 'job', action: 'index')
        get "/api/v1/jobs/$id"(controller: 'job', action: 'show')
        post "/api/v1/jobs/$id/cancel"(controller: 'job', action: 'cancel')
        post "/api/v1/jobs/$id/rerun"(controller: 'job', action: 'rerun')
        get "/api/v1/batches/$id"(controller: 'jobBatch', action: 'show')
        post "/api/v1/batches/$id/retry"(controller: 'jobBatch', action: 'retry')
        post "/api/v1/batches/$id/continue"(controller: 'jobBatch', action: 'continueRollout')
        get "/api/v1/groups/$id/batches"(controller: 'jobBatch', action: 'forGroup')

        post "/api/v1/devices/$id/remote-session"(controller: 'remoteSession', action: 'open')
        post "/api/v1/devices/$id/shell-session"(controller: 'remoteSession', action: 'openShell')
        get "/api/v1/devices/$id/remote-sessions"(controller: 'remoteSession', action: 'forDevice')
        get "/api/v1/remote-sessions/$id"(controller: 'remoteSession', action: 'show')
        delete "/api/v1/remote-sessions/$id"(controller: 'remoteSession', action: 'close')

        get "/api/v1/enrollment-requests"(controller: 'enrollmentRequest', action: 'index')
        post "/api/v1/enrollment-requests/$id/approve"(controller: 'enrollmentRequest', action: 'approve')
        post "/api/v1/enrollment-requests/$id/deny"(controller: 'enrollmentRequest', action: 'deny')

        get "/api/v1/audit"(controller: 'audit', action: 'index')

        get "/api/v1/notification-channels"(controller: 'notificationChannel', action: 'index')
        post "/api/v1/notification-channels"(controller: 'notificationChannel', action: 'save')
        put "/api/v1/notification-channels/$id"(controller: 'notificationChannel', action: 'update')
        delete "/api/v1/notification-channels/$id"(controller: 'notificationChannel', action: 'delete')
        post "/api/v1/notification-channels/$id/test"(controller: 'notificationChannel', action: 'test')

        get "/api/v1/users"(controller: 'user', action: 'index')
        post "/api/v1/users"(controller: 'user', action: 'save')
        put "/api/v1/users/$id"(controller: 'user', action: 'update')

        get "/api/v1/enrollment-tokens"(controller: 'enrollmentToken', action: 'index')
        post "/api/v1/enrollment-tokens"(controller: 'enrollmentToken', action: 'save')
        delete "/api/v1/enrollment-tokens/$id"(controller: 'enrollmentToken', action: 'delete')

        // Public, unauthenticated demo page + JSON status for the kiosk's
        // local page (which must keep working when this server is down).
        get "/kiosk-demo/ping"(controller: 'kioskDemo', action: 'ping')
        get "/kiosk-demo/$id"(controller: 'kioskDemo', action: 'show')
        get "/kiosk-demo/$id/status"(controller: 'kioskDemo', action: 'status')

        // Public agent distribution for the one-step enrollment command.
        get "/install.sh"(controller: 'agentInstall', action: 'script')
        get "/install/agent/$file"(controller: 'agentInstall', action: 'binary')

        "/"(controller: 'application', action: 'index')
        "500"(view: '/error')
        "404"(view: '/notFound')
    }
}
