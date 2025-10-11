plugins {
    id("java-library")
    id("application")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.kotlinter)
    id("jacoco")
    alias(libs.plugins.git.version) // https://stackoverflow.com/a/71212144
    alias(libs.plugins.sonatype.maven.central)
    alias(libs.plugins.gradleup.nmcp.aggregation)
}

dependencies {
    implementation(libs.jetbrains.kotlinx.serialization.json)
    implementation(libs.bundles.ktor)

    // don't expose this externally, we only need it for the main
    api(libs.clikt)
    api(libs.slf4j.simple)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.jasonernst.krcon.MainKt")
}

// In build.gradle.kts
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs("-Dorg.gradle.console=plain")
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.withType<Test>().configureEach {
    // useJUnitPlatform()
    finalizedBy("jacocoTestReport")
}

jacoco {
    toolVersion = "0.8.14"
}

version = "0.0.0-SNAPSHOT"
gitVersioning.apply {
    refs {
        branch(".+") { version = "\${ref}-SNAPSHOT" }
        tag("(?<version>.*)") { version = "\${ref.version}" }
    }
}

// see: https://github.com/vanniktech/gradle-maven-publish-plugin/issues/747#issuecomment-2066762725
// and: https://github.com/GradleUp/nmcp
nmcpAggregation {
    val props = project.properties
    centralPortal {
        username = props["centralPortalToken"] as String? ?: ""
        password = props["centralPortalPassword"] as String? ?: ""
        // or if you want to publish automatically
        publishingType = "AUTOMATIC"
    }
}

// see: https://vanniktech.github.io/gradle-maven-publish-plugin/central/#configuring-the-pom
mavenPublishing {
    coordinates("com.jasonernst.krcon", "krcon", version.toString())
    pom {
        name = "krcon"
        description = "A library to make RCON connections to game servers."
        inceptionYear = "2024"
        url = "https://github.com/compscidr/krcon"
        licenses {
            license {
                name = "GPL-3.0"
                url = "https://www.gnu.org/licenses/gpl-3.0.en.html"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "compscidr"
                name = "Jason Ernst"
                url = "https://www.jasonernst.com"
            }
        }
        scm {
            url = "https://github.com/compscidr/krcon"
            connection = "scm:git:git://github.com/compscidr/krcon.git"
            developerConnection = "scm:git:ssh://git@github.com/compscidr/krcon.git"
        }
    }

    signAllPublications()
}