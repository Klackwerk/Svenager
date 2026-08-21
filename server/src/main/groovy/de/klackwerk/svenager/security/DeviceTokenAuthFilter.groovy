package de.klackwerk.svenager.security

import de.klackwerk.svenager.Device
import de.klackwerk.svenager.DeviceStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates agent requests via their per-device bearer token.
 * On success the request carries a ROLE_DEVICE authentication whose principal
 * is the device UUID, and the device database id as a request attribute.
 */
@Component
class DeviceTokenAuthFilter extends OncePerRequestFilter {

    static final String DEVICE_ID_ATTRIBUTE = 'svenager.deviceId'

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        String header = request.getHeader('Authorization')
        if (header?.startsWith('Bearer ')) {
            String tokenHash = Tokens.hash(header.substring('Bearer '.length()).trim())
            Device.withTransaction {
                Device device = Device.findByTokenHash(tokenHash)
                if (device != null && device.status == DeviceStatus.ACTIVE) {
                    def auth = new UsernamePasswordAuthenticationToken(
                            device.uuid, null, [new SimpleGrantedAuthority('ROLE_DEVICE')])
                    SecurityContextHolder.context.authentication = auth
                    request.setAttribute(DEVICE_ID_ATTRIBUTE, device.id)
                }
            }
        }
        chain.doFilter(request, response)
    }
}
