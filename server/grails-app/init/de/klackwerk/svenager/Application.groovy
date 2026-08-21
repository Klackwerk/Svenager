package de.klackwerk.svenager

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration

import groovy.transform.CompileStatic
import org.springframework.context.annotation.ComponentScan
import org.springframework.scheduling.annotation.EnableScheduling

@CompileStatic
@EnableScheduling
@ComponentScan(['de.klackwerk.svenager.security', 'de.klackwerk.svenager.tunnel',
        'de.klackwerk.svenager.schedule', 'de.klackwerk.svenager.db'])
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        GrailsApp.run(Application, args)
    }
}
