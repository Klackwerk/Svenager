package de.klackwerk.svenager

import de.klackwerk.svenager.security.Tokens
import grails.core.GrailsApplication
import grails.util.Environment
import groovy.util.logging.Slf4j
import org.springframework.security.crypto.password.PasswordEncoder

@Slf4j
class BootStrap {

    GrailsApplication grailsApplication
    PasswordEncoder passwordEncoder
    RemoteSessionService remoteSessionService

    def init = { servletContext ->
        remoteSessionService.closeAllOpen('server restarted')
        User.withTransaction {
            if (User.count() > 0) {
                return
            }
            String password = System.getenv('SVENAGER_ADMIN_PASSWORD')
            boolean generated = false
            if (!password) {
                if (Environment.current in [Environment.DEVELOPMENT, Environment.TEST]) {
                    password = 'admin'
                } else {
                    password = Tokens.generate('svpw')
                    generated = true
                }
            }
            new User(username: 'admin', passwordHash: passwordEncoder.encode(password), role: UserRole.ADMIN)
                    .save(failOnError: true, flush: true)
            if (generated) {
                // Logged once at first boot; SVENAGER_ADMIN_PASSWORD avoids this.
                log.warn("Created initial admin user 'admin' with generated password: {} — change it immediately", password)
            } else {
                log.info("Created initial admin user 'admin'")
            }
            // First startup only: the reference repository gives a new
            // instance assignable roles out of the box; the background
            // sync picks it up. Delete it in the UI if unwanted.
            String defaultRepoUrl = grailsApplication.config.getProperty('svenager.repos.defaultUrl')
            if (defaultRepoUrl) {
                new AnsibleRepository(name: 'Svenager reference', gitUrl: defaultRepoUrl)
                        .save(failOnError: true, flush: true)
                log.info("Registered default Ansible repository: {}", defaultRepoUrl)
            }
        }
    }

    def destroy = {
    }
}
