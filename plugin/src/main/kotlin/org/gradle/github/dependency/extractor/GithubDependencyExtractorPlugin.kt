/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package org.gradle.github.dependency.extractor

import org.gradle.api.Plugin
import org.gradle.api.internal.GradleInternal
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.github.dependency.extractor.internal.DependencyExtractorService
import org.gradle.github.dependency.extractor.internal.DependencyExtractorService_6_1
import org.gradle.internal.build.event.BuildEventListenerRegistryInternal
import org.gradle.internal.operations.BuildOperationListenerManager
import org.gradle.util.GradleVersion
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A plugin that extracts the dependencies from the Gradle build and exports it using the GitHub API format.
 */
@Suppress("unused")
class GithubDependencyExtractorPlugin : Plugin<Gradle> {
    private companion object {
        private val LOGGER = LoggerFactory.getLogger(GithubDependencyExtractorPlugin::class.java)
        private const val ENV_VIA_PARAMETER_PREFIX = "org.gradle.github.internal.debug.env."

        /**
         * Environment variable should be set to the workspace directory that the Git repository is checked out in.
         */
        const val ENV_GITHUB_WORKSPACE = "GITHUB_WORKSPACE"

        fun throwEnvironmentVariableMissingException(variable: String): Nothing {
            throw IllegalStateException("The environment variable '$variable' must be set, but it was not.")
        }

        private inline fun <reified T> Gradle.service(): T =
            (this as GradleInternal).services.get(T::class.java)

        private inline val Gradle.objectFactory: ObjectFactory
            get() = service()

        private inline val Gradle.providerFactory: ProviderFactory
            get() = service()
    }

    override fun apply(gradle: Gradle) {
        println("Applying Plugin: GithubDependencyExtractorPlugin")
        if (gradle.parent != null) {
            println("Not applying plugin to included build")
            return
        }

        val gradleVersion = GradleVersion.current()
        // Create the adapter based upon the version of Gradle
        val applicatorStrategy = when {
            gradleVersion >= GradleVersion.version("6.1") -> PluginApplicatorStrategy.PluginApplicatorStrategy_6_1
            else -> PluginApplicatorStrategy.DefaultPluginApplicatorStrategy
        }

        // Create the service
        val extractorServiceProvider = applicatorStrategy.createExtractorService(gradle)

        gradle.rootProject { project ->
            extractorServiceProvider
                .get()
                .setRootProjectBuildDirectory(project.buildDir)
        }

        // Register the service to listen for Build Events
        applicatorStrategy.registerExtractorListener(gradle, extractorServiceProvider)

        // Register the shutdown hook that should execute at the completion of the Gradle build.
        applicatorStrategy.registerExtractorServiceShutdown(gradle, extractorServiceProvider)
    }

    /**
     * Adapters for creating the [DependencyExtractorService] and installing it into [Gradle] based upon the Gradle version.
     */
    private interface PluginApplicatorStrategy {

        fun createExtractorService(
            gradle: Gradle
        ): Provider<out DependencyExtractorService>

        fun registerExtractorListener(
            gradle: Gradle,
            extractorServiceProvider: Provider<out DependencyExtractorService>
        )

        fun registerExtractorServiceShutdown(
            gradle: Gradle,
            extractorServiceProvider: Provider<out DependencyExtractorService>
        )

        object DefaultPluginApplicatorStrategy : PluginApplicatorStrategy {
            override fun createExtractorService(
                gradle: Gradle
            ): Provider<out DependencyExtractorService> {
                val providerFactory = gradle.providerFactory

                val gitWorkspaceEnvVar = System.getenv()[ENV_GITHUB_WORKSPACE]
                    ?: gradle.startParameter.projectProperties[ENV_VIA_PARAMETER_PREFIX + ENV_GITHUB_WORKSPACE]
                    ?: throwEnvironmentVariableMissingException(ENV_GITHUB_WORKSPACE)

                val gitWorkspaceDirectory = Paths.get(gitWorkspaceEnvVar)
                // Create a constant value that the provider will always return
                val constantDependencyExtractorService = object : DependencyExtractorService() {
                    override val gitWorkspaceDirectory: Path
                        get() = gitWorkspaceDirectory
                }
                return providerFactory.provider { constantDependencyExtractorService }
            }

            override fun registerExtractorListener(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractorService>
            ) {
                gradle
                    .service<BuildOperationListenerManager>()
                    .addListener(extractorServiceProvider.get())
            }

            override fun registerExtractorServiceShutdown(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractorService>
            ) {
                gradle.buildFinished {
                    extractorServiceProvider.get().close()
                    gradle.service<BuildOperationListenerManager>()
                        .removeListener(extractorServiceProvider.get())
                }
            }
        }

        @Suppress("ClassName")
        object PluginApplicatorStrategy_6_1 : PluginApplicatorStrategy {
            private const val SERVICE_NAME = "dependencyExtractorService"

            override fun createExtractorService(
                gradle: Gradle
            ): Provider<out DependencyExtractorService> {
                val providerFactory = gradle.service<ProviderFactory>()
                val objectFactory = gradle.service<ObjectFactory>()
                val gitWorkspaceEnvVar =
                    providerFactory.environmentVariable(ENV_GITHUB_WORKSPACE)
                        .orElse(
                            providerFactory.gradleProperty(ENV_VIA_PARAMETER_PREFIX + ENV_GITHUB_WORKSPACE)
                        )
                        .orElse(
                            providerFactory.provider { throwEnvironmentVariableMissingException(ENV_GITHUB_WORKSPACE) }
                        ).map { File(it) }

                val gitWorkspaceDirectory =
                    gitWorkspaceEnvVar.flatMap {
                        objectFactory.directoryProperty().apply {
                            set(it)
                        }
                    }

                return gradle.sharedServices.registerIfAbsent(
                    SERVICE_NAME,
                    DependencyExtractorService_6_1::class.java
                ) { spec ->
                    spec.parameters { it.gitWorkspaceDirectory.convention(gitWorkspaceDirectory) }
                }
            }

            override fun registerExtractorListener(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractorService>
            ) {
                gradle.service<BuildEventListenerRegistryInternal>()
                    .onOperationCompletion(extractorServiceProvider)
            }

            override fun registerExtractorServiceShutdown(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractorService>
            ) {
                // No-op as DependencyExtractorService is Auto-Closable
            }
        }
    }
}
