// Top-level build file where you can add configuration options common to all subprojects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    id("org.sonarqube")
    id("jacoco")
    alias(libs.plugins.detekt)
}

repositories {
    google()
    mavenCentral()
}

allprojects {
    apply(plugin = "jacoco")
}

subprojects {
    tasks.register<JacocoReport>("jacocoTestReport") {
        description = "Generate Jacoco coverage reports into the 'reports/jacoco' directory."
        group = "Reporting"
        val testTaskName = if (plugins.hasPlugin("com.android.application") || plugins.hasPlugin("com.android.library")) {
            "testDebugUnitTest"
        } else {
            "test"
        }

        tasks.findByName(testTaskName)?.let { dependsOn(it) }

        reports {
            xml.required.set(true)
            html.required.set(true)
        }

        val fileFilter = listOf(
            "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
            "**/*Test*.*", "android/**/*.*", "**/databinding/**/*.*", "**/BR.class"
        )
        val debugTree = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
            exclude(fileFilter)
        }
        val mainSrc = "${project.projectDir}/src/main/java"

        sourceDirectories.setFrom(files(mainSrc))
        classDirectories.setFrom(files(debugTree))
        executionData.setFrom(fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec", "jacoco/test.exec")
        })
    }

    apply(plugin = "io.gitlab.arturbosch.detekt")
    detekt {
        toolVersion = "1.23.7"
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        ignoreFailures = true
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "sogeuni_SnapTune")
        property("sonar.organization", "sogeuni")
        property("sonar.host.url", "https://sonarcloud.io")

        // Gradle property(gradle.properties) or Environment Variable
        (findProperty("sonar.token") ?: System.getenv("SONAR_TOKEN"))?.let {
            property("sonar.token", it)
        }

        property("sonar.coverage.jacoco.xmlReportPaths", "${project.projectDir}/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
    }
}
