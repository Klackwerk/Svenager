package de.klackwerk.svenager.db

import groovy.transform.CompileStatic
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

import javax.sql.DataSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the Flyway migrations the moment the dataSource bean exists —
 * before GORM/Hibernate issues its first query. Grails does not activate
 * Spring Boot's Flyway autoconfiguration and manages the datastore
 * initialization order itself, so this hooks bean post-processing instead
 * of bean dependencies. Settings come from spring.flyway.* in
 * application.yml.
 */
@CompileStatic
@Configuration
class FlywayConfig {

    @Bean
    static BeanPostProcessor flywayMigrator(Environment environment) {
        AtomicBoolean migrated = new AtomicBoolean(false)
        new BeanPostProcessor() {
            @Override
            Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource && beanName == 'dataSource'
                        && environment.getProperty('spring.flyway.enabled', Boolean, false)
                        && migrated.compareAndSet(false, true)) {
                    Flyway.configure()
                            .dataSource((DataSource) bean)
                            .locations('classpath:db/migration')
                            .baselineOnMigrate(environment.getProperty(
                                    'spring.flyway.baseline-on-migrate', Boolean, true))
                            .baselineVersion(environment.getProperty(
                                    'spring.flyway.baseline-version', '1'))
                            .load()
                            .migrate()
                }
                bean
            }
        }
    }
}
