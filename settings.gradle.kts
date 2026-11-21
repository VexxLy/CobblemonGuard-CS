pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        mavenCentral()
        gradlePluginPortal()
    }
    // Mismo criterio que cobblemmo-server y cobblemmo-client: Loom fijo, sin SNAPSHOT.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "fabric-loom") {
                useModule("net.fabricmc:fabric-loom:${requested.version}")
            }
        }
    }
}

rootProject.name = "CobblemonGUARD"
