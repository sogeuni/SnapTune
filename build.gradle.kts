// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("org.sonarqube")
}

sonar {
    properties {
        property("sonar.projectKey", "sogeuni_SnapTune")
        property("sonar.organization", "sogeuni")
        property("sonar.host.url", "https://sonarcloud.io")
    }
}
