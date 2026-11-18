rootProject.name = "Phone"
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
    // (Fossify Commons, kept in this repository: its own library versions)
    versionCatalogs {
        create("clibs") {
            from(files("gradle/commons.versions.toml"))
        }
    }
}
include(":app")
include(":commons")
