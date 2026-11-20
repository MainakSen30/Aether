pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Aether"

include(":app")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:assets")
include(":core:theme")
include(":core:designsystem")
include(":feature:launcher")
