package de.klackwerk.svenager

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Parses a checked-out Ansible repository into role metadata for the UI.
 * Pure parsing — repository content is never executed. See
 * ansible/README.md for the repository convention this reads.
 */
class RepoAnalyzerService {

    static final long MAX_YAML_BYTES = 512 * 1024

    /**
     * @return [roles: List<Map>, warnings: List<String>] where each role map
     * has name, displayName, description, userAssignable, argumentSpec (Map),
     * defaults (Map).
     */
    Map analyze(File repoDir) {
        List<String> warnings = []
        Map svenagerConfig = parseYamlFile(new File(repoDir, 'svenager.yml'), warnings) ?: [:]
        Map perRole = (svenagerConfig.roles instanceof Map) ? svenagerConfig.roles as Map : [:]

        File rolesDir = new File(repoDir, 'roles')
        if (!rolesDir.directory) {
            warnings << 'Repository has no roles/ directory.'
            return [roles: [], warnings: warnings]
        }

        List<Map> roles = []
        rolesDir.listFiles({ File f -> f.directory && !f.name.startsWith('.') } as FileFilter)
                ?.sort { it.name }
                ?.each { File roleDir ->
                    roles << analyzeRole(roleDir, perRole[roleDir.name] instanceof Map ? perRole[roleDir.name] as Map : [:], warnings)
                }
        [roles: roles, warnings: warnings]
    }

    private Map analyzeRole(File roleDir, Map overrides, List<String> warnings) {
        Map meta = parseYamlFile(new File(roleDir, 'meta/main.yml'), warnings) ?: [:]
        Map galaxyInfo = meta.galaxy_info instanceof Map ? meta.galaxy_info as Map : [:]

        Map argumentSpecs = parseYamlFile(new File(roleDir, 'meta/argument_specs.yml'), warnings) ?: [:]
        Map mainSpec = [:]
        if (argumentSpecs.argument_specs instanceof Map) {
            Map specs = argumentSpecs.argument_specs as Map
            mainSpec = specs.main instanceof Map ? specs.main as Map : [:]
        }
        Map options = mainSpec.options instanceof Map ? mainSpec.options as Map : [:]

        Map defaults = parseYamlFile(new File(roleDir, 'defaults/main.yml'), warnings) ?: [:]

        [
                name          : roleDir.name,
                displayName   : overrides.display_name ?: null,
                description   : overrides.description ?: mainSpec.short_description ?: galaxyInfo.description ?: null,
                userAssignable: overrides.containsKey('user_assignable') ? overrides.user_assignable as boolean : true,
                baseRole      : overrides.base as boolean,
                argumentSpec  : options,
                defaults      : defaults,
        ]
    }

    private Map parseYamlFile(File file, List<String> warnings) {
        if (!file.file) {
            return null
        }
        if (file.length() > MAX_YAML_BYTES) {
            warnings << "Skipped ${file.name}: larger than ${MAX_YAML_BYTES / 1024} KiB.".toString()
            return null
        }
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(file.text)
            return parsed instanceof Map ? parsed as Map : null
        } catch (Exception e) {
            warnings << "Could not parse ${relativeName(file)}: ${e.message?.readLines()?.first()}".toString()
            return null
        }
    }

    private static String relativeName(File file) {
        "${file.parentFile?.parentFile?.name}/${file.parentFile?.name}/${file.name}"
    }
}
