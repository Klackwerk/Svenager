package de.klackwerk.svenager

import spock.lang.Specification
import spock.lang.TempDir

class RepoAnalyzerServiceSpec extends Specification {

    @TempDir
    File repoDir

    RepoAnalyzerService service = new RepoAnalyzerService()

    private void writeFile(String path, String content) {
        File file = new File(repoDir, path)
        file.parentFile.mkdirs()
        file.text = content
    }

    void "analyzes roles with argument specs, defaults and svenager.yml overrides"() {
        given:
        writeFile('svenager.yml', '''
roles:
  kiosk_browser:
    display_name: "Kiosk browser"
  svenager_base:
    user_assignable: false
''')
        writeFile('roles/kiosk_browser/meta/main.yml', '''
galaxy_info:
  description: Configures a fullscreen browser kiosk
''')
        writeFile('roles/kiosk_browser/meta/argument_specs.yml', '''
argument_specs:
  main:
    short_description: Fullscreen browser kiosk
    options:
      kiosk_url:
        type: str
        required: true
        description: The URL the kiosk shows
      kiosk_zoom:
        type: float
        default: 1.0
''')
        writeFile('roles/kiosk_browser/defaults/main.yml', 'kiosk_zoom: 1.0\n')
        writeFile('roles/svenager_base/meta/main.yml', 'galaxy_info:\n  description: Base setup\n')

        when:
        Map result = service.analyze(repoDir)

        then:
        result.warnings.empty
        result.roles.size() == 2

        with(result.roles.find { it.name == 'kiosk_browser' }) {
            displayName == 'Kiosk browser'
            description == 'Fullscreen browser kiosk'
            userAssignable
            argumentSpec.kiosk_url.required
            argumentSpec.kiosk_url.type == 'str'
            defaults.kiosk_zoom == 1.0
        }
        with(result.roles.find { it.name == 'svenager_base' }) {
            !userAssignable
            description == 'Base setup'
        }
    }

    void "collects warnings for broken YAML instead of failing"() {
        given:
        writeFile('roles/broken/meta/main.yml', "galaxy_info: [unclosed\n")
        writeFile('roles/ok/meta/main.yml', 'galaxy_info:\n  description: Fine\n')

        when:
        Map result = service.analyze(repoDir)

        then:
        result.roles.size() == 2
        result.warnings.size() == 1
        result.roles.find { it.name == 'ok' }.description == 'Fine'
        result.roles.find { it.name == 'broken' }.description == null
    }

    void "reports a repository without roles directory"() {
        expect:
        service.analyze(repoDir).roles.empty
        service.analyze(repoDir).warnings
    }
}
