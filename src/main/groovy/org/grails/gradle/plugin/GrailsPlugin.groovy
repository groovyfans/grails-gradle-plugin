/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.grails.gradle.plugin

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.internal.ConventionMapping
import org.gradle.api.tasks.Sync
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.grails.gradle.plugin.internal.DefaultGrailsProject

class GrailsPlugin implements Plugin<Project> {
    static public final GRAILS_TASK_PREFIX = "grails-"
    static public final GRAILS_ARGS_PROPERTY = 'grailsArgs'
    static public final GRAILS_ENV_PROPERTY = 'grailsEnv'

    void apply(Project project) {
        DefaultGrailsProject grailsProject = project.extensions.create("grails", DefaultGrailsProject, project)
        grailsProject.conventionMapping.with {
            map("projectDir") { project.projectDir }
            map("projectWorkDir") { project.buildDir }
        }

        Configuration bootstrapConfiguration = getOrCreateConfiguration(project, "bootstrap")

        Configuration compileConfiguration = getOrCreateConfiguration(project, "compile")
        Configuration providedConfiguration = getOrCreateConfiguration(project, "provided")
        Configuration runtimeConfiguration = getOrCreateConfiguration(project, "runtime")
        Configuration testConfiguration = getOrCreateConfiguration(project, "test")
        Configuration resourcesConfiguration = getOrCreateConfiguration(project, "resources")
        Configuration springloadedConfiguration = getOrCreateConfiguration(project, "springloaded")

        runtimeConfiguration.extendsFrom(compileConfiguration)
        testConfiguration.extendsFrom(runtimeConfiguration)

        grailsProject.onSetGrailsVersion { String grailsVersion ->
            def dependenciesUtil = new GrailsDependenciesConfigurer(project, grailsProject.grailsVersion)
            dependenciesUtil.configureBootstrapClasspath(bootstrapConfiguration)
            dependenciesUtil.configureCompileClasspath(compileConfiguration)
            dependenciesUtil.configureResources(resourcesConfiguration)
        }

        Sync unpackResources = project.task("unpackGrailsResources", type: Sync)
        unpackResources.with {
            dependsOn resourcesConfiguration
            from { project.zipTree(resourcesConfiguration.singleFile) }
            into { "$project.buildDir/grails/resources" }
            doLast {
                def file = new File(destinationDir, "scripts/log4j.properties")
                file.parentFile.mkdirs()
                file.withOutputStream { out ->
                    getClass().getResourceAsStream("/grails-maven/log4j.properties").withStream {
                        out << it
                    }
                }
            }
        }

        project.tasks.withType(GrailsTask) { GrailsTask task ->
            dependsOn unpackResources
            ConventionMapping conventionMapping = task.conventionMapping
            conventionMapping.with {
                map("grailsHome") { unpackResources.destinationDir }
                map("projectDir") { grailsProject.projectDir }
                map("projectWorkDir") { grailsProject.projectWorkDir }
                map("grailsVersion") { grailsProject.grailsVersion }

                map("bootstrapClasspath") { bootstrapConfiguration }

                map("providedClasspath") { providedConfiguration }
                map("compileClasspath") { compileConfiguration }
                map("runtimeClasspath") { runtimeConfiguration }
                map("testClasspath") { testConfiguration }

                map("springloaded") {
                    if (springloadedConfiguration.dependencies.empty) {
                        def defaultSpringloaded = project.dependencies.create("org.springsource.springloaded:springloaded-core:$grailsProject.springLoadedVersion")
                        springloadedConfiguration.dependencies.add(defaultSpringloaded)
                    }

                    def lenient = springloadedConfiguration.resolvedConfiguration.lenientConfiguration
                    if (lenient.unresolvedModuleDependencies) {
                        def springloadedDependency = springloadedConfiguration.dependencies.toList().first()
                        project.logger.warn("Failed to resolve springloaded dependency: $springloadedDependency (reloading will be disabled)")
                        null
                    } else {
                        springloadedConfiguration
                    }
                }
            }

            doFirst {
                if (grailsProject.grailsVersion == null) {
                    throw new InvalidUserDataException("You must set 'grails.grailsVersion' property before Grails tasks can be run")
                }
            }
        }

        project.task("init", type: GrailsTask) {
            onlyIf {
                !project.file("application.properties").exists() && !project.file("grails-app").exists()
            }

            doFirst {
                if (project.version == "unspecified") {
                    throw new InvalidUserDataException("[GrailsPlugin] Build file must specify a 'version' property.")
                }
            }

            def projName = project.hasProperty(GRAILS_ARGS_PROPERTY) ? project.property(GRAILS_ARGS_PROPERTY) : project.projectDir.name

            command "create-app"
            args "--inplace --appVersion=$project.version $projName"
        }

        project.task("clean", type: GrailsTask, overwrite: true)

        project.task("test", type: GrailsTask, overwrite: true) {
            command "test-app"
        }

        project.task("assemble", type: GrailsTask, overwrite: true) {
            command pluginProject ? "package-plugin" : "war"
        }

        // Convert any task executed from the command line
        // with the special prefix into the Grails equivalent command.
        project.gradle.afterProject { p, ex ->
            if (p == project) {
                project.tasks.addRule("Grails command") { String name ->
                    if (name.startsWith(GRAILS_TASK_PREFIX)) {
                        project.task(name, type: GrailsTask) {
                            command name - GRAILS_TASK_PREFIX
                            if (project.hasProperty(GRAILS_ARGS_PROPERTY)) {
                              args project.property(GRAILS_ARGS_PROPERTY)
                            }
                            if (project.hasProperty(GRAILS_ENV_PROPERTY)) {
                              env project.property(GRAILS_ENV_PROPERTY)
                            }
                        }
                    }
                }
            }
        }

        configureIdea(project)
    }

    void configureIdea(Project project) {
        project.plugins.withType(IdeaPlugin) {
            project.idea {
                def configurations = project.configurations
                module.scopes = [
                        PROVIDED: [plus: [configurations.provided], minus: []],
                        COMPILE: [plus: [configurations.compile], minus: []],
                        RUNTIME: [plus: [configurations.runtime], minus: [configurations.compile]],
                        TEST: [plus: [configurations.test], minus: [configurations.runtime]]
                ]
            }
        }
    }

    Configuration getOrCreateConfiguration(Project project, String name) {
        ConfigurationContainer container = project.configurations
        container.findByName(name) ?: container.create(name)
    }
}
