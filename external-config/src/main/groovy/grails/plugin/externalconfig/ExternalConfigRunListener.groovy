package grails.plugin.externalconfig

import grails.util.Environment
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.config.PropertySourcesConfig
import org.grails.config.yaml.YamlPropertySourceLoader
import org.springframework.boot.ConfigurableBootstrapContext
import org.springframework.boot.SpringApplication
import org.springframework.boot.SpringApplicationRunListener
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path

@CompileStatic
@Slf4j(category = 'grails.plugin.externalconfig.ExternalConfig')
class ExternalConfigRunListener implements SpringApplicationRunListener {

    private ResourceLoader defaultResourceLoader = new DefaultResourceLoader()
    private YamlPropertySourceLoader yamlPropertySourceLoader = new YamlPropertySourceLoader()
    private PropertiesPropertySourceLoader propertiesPropertySourceLoader = new PropertiesPropertySourceLoader()
    private String userHome = System.properties.getProperty('user.home')
    private String separator = System.properties.getProperty('file.separator')

    final SpringApplication application

    ExternalConfigRunListener(SpringApplication application, String[] args) {
        this.application = application
    }

    @Override
    void environmentPrepared(ConfigurableBootstrapContext bootstrapContext, ConfigurableEnvironment environment) {
        List<Object> locations = getLocations(environment)

        String encoding = environment.getProperty('grails.config.encoding', String, 'UTF-8')

        for (location in locations) {
            List<PropertySource<?>> propertySources = []
            Map currentProperties = getCurrentConfig(environment)
            if (location instanceof Class) {
                propertySources = loadClassConfig(location as Class, currentProperties)
            } else {
                // Replace placeholders from known locations
                String finalLocation = environment.resolvePlaceholders(location as String)

                Resource resource = defaultResourceLoader.getResource(finalLocation)
                if (resource.exists()) {
                    if (finalLocation.endsWith('.groovy')) {
                        propertySources = loadGroovyConfig(resource, encoding, currentProperties)
                    } else if (finalLocation.endsWith('.yml')) {
                        environment.activeProfiles
                        propertySources = loadYamlConfig(resource)
                    } else {
                        // Attempt to load the config as plain old properties file (POPF)
                        propertySources = loadPropertiesConfig(resource)
                    }
                } else {
                    log.debug("Config file {} not found", [finalLocation] as Object[])
                }
            }
            propertySources.each {
                environment.propertySources.addFirst(it)
            }
        }

        setMicronautConfigLocations(locations)
    }

    // Resolve final locations, taking into account user home prefix and file wildcards
    private List<Object> getLocations(ConfigurableEnvironment environment) {
        List<Object> locations = environment.getProperty('grails.config.locations', List, []) as List<Object>
        // See if grails.config.locations is defined in an environments block like 'development' or 'test'
        String environmentString = "environments.${Environment.current.name}.grails.config.locations"
        locations = environment.getProperty(environmentString, List, locations)
        locations.collectMany { Object location ->
            if (location instanceof CharSequence) {
                location = replaceUserHomePrefix(location as String)
                List<Object> expandedLocations = handleWildcardLocation(location as String)
                if (expandedLocations) {
                    return expandedLocations
                }
            }
            return [location]
        }
    }

    // Expands wildcards if any
    private List<Object> handleWildcardLocation(String location) {
        if (location.startsWith('file:')) {
            String locationFileName = location.tokenize(separator)[-1]
            if (locationFileName.contains('*')) {
                String parentLocation = location - locationFileName
                try {
                    Resource resource = defaultResourceLoader.getResource(parentLocation)
                    if (resource.file.exists() && resource.file.isDirectory()) {
                        Path dir = resource.file.toPath()
                        DirectoryStream<Path> stream = Files.newDirectoryStream(dir, locationFileName)

                        return stream.collect { Path p ->
                            "file:${p.toAbsolutePath()}"
                        } as List<Object>
                    }
                } catch (FileNotFoundException ignore) {
                    return null
                }
            }
        }
        return null
    }

    // Replace ~ with value from system property 'user.home' if set
    private String replaceUserHomePrefix(String location) {
        if (userHome && location.startsWith('~/')) {
            location = "file:${userHome}${location[1..-1]}"
        }
        return location
    }

    // Load groovy config from classpath
    private static List<PropertySource<?>> loadClassConfig(Class location, Map currentConfig) {
        log.info("Loading config class {}", location.name)
        ConfigSlurper slurper = new ConfigSlurper(Environment.current.name)
        WriteFilteringMap filterMap = new WriteFilteringMap(currentConfig)
        slurper.binding = filterMap
        Map properties = slurper.parse((Class) location)?.flatten()
        Map writtenValues = filterMap.getWrittenValues()
        properties.putAll(writtenValues)
        return [new MapPropertySource(location.toString(), properties)] as List<PropertySource<?>>
    }

    // Load groovy config from resource
    private static List<PropertySource<?>> loadGroovyConfig(Resource resource, String encoding, Map currentConfig) {
        log.info("Loading groovy config file {}", resource.URI)
        String configText = resource.inputStream.getText(encoding)
        ConfigSlurper slurper = new ConfigSlurper(Environment.current.name)
        WriteFilteringMap filterMap = new WriteFilteringMap(currentConfig)
        slurper.binding = filterMap
        ConfigObject configObject = slurper.parse(configText)
        Map<String, Object> properties = configText ? configObject?.flatten() as Map<String, Object> : [:]
        Map writtenValues = filterMap.getWrittenValues()
        properties.putAll(writtenValues)
        return [new MapPropertySource(resource.filename, properties)] as List<PropertySource<?>>
    }

    private List<PropertySource<?>> loadYamlConfig(Resource resource) {
        log.info("Loading YAML config file {}", resource.URI)
        return yamlPropertySourceLoader.load(resource.filename, resource, null)
    }

    private List<PropertySource<?>> loadPropertiesConfig(Resource resource) {
        log.info("Loading properties config file {}", resource.URI)
        return propertiesPropertySourceLoader.load(resource.filename, resource)
    }

    private void setMicronautConfigLocations(List<Object> newSources) {
        List<String> sources = System.getProperty('micronaut.config.files', System.getenv('MICRONAUT_CONFIG_FILES') ?: '').tokenize(',')
        sources.addAll(newSources.collect { it.toString() })
        sources = filterMissingMicronautLocations(sources)
        log.debug("Setting 'micronaut.config.files' to ${sources.join(',')}")
        System.setProperty('micronaut.config.files', sources.join(','))
    }

    private List<String> filterMissingMicronautLocations(List<String> sources) {
        sources.findAll { String location ->
            try {
                def resource = defaultResourceLoader.getResource(location)
                if (!resource.exists()) {
                    log.debug("Configuration file ${location} not found, ignoring.")
                    return false
                }
            } catch (FileNotFoundException ignore) {
                log.debug("Configuration file ${location} not found, ignoring.")
                return false
            }
            true
        }
    }


    static Map getCurrentConfig(ConfigurableEnvironment environment) {
        return new PropertySourcesConfig(environment.propertySources)
    }

}
