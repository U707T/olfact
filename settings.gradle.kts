pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "olfact"
include(":app", ":core", ":web", ":player", ":download", ":ai")
