import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "A pure-Java MP3 decoder (MPEG-1/2/2.5 Layer III): a faithful port of minimp3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // The library has no dependencies; JUnit is only used by the tests.
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.compileJava {
    options.compilerArgs.add("-Werror")
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("-release", "21")
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    from("LICENSE") {
        into("META-INF")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "com.radiomod.mp3",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

// ---------------------------------------------------------------------------------------------------
// Test data: the C reference decoder, the synthetic corpus and the reference outputs (see reference/).
// Needs Python 3, git, a C compiler and ffmpeg with libmp3lame. Properties:
//   -Ppython=...  -Pcc=...  -Pffmpeg=...  -Pminimp3=<existing minimp3 checkout>
//   -PskipTestData  run only the tests that need no generated data (the others are skipped)
// ---------------------------------------------------------------------------------------------------
val testDataDir = layout.buildDirectory.dir("testdata")
val reportsDir = layout.buildDirectory.dir("reports/minimp3")
val skipTestData = providers.gradleProperty("skipTestData").isPresent

val prepareTestData = tasks.register<Exec>("prepareTestData") {
    group = "verification"
    description = "Builds the C reference decoder, generates the corpus and the reference outputs."
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    val command = mutableListOf(
        providers.gradleProperty("python").getOrElse(if (windows) "python" else "python3"),
        file("reference/prepare_testdata.py").absolutePath,
        "--out", testDataDir.get().asFile.absolutePath,
    )
    for (option in listOf("cc", "ffmpeg", "minimp3")) {
        providers.gradleProperty(option).orNull?.let { command += listOf("--$option", it) }
    }
    commandLine(command)
    inputs.files("reference/prepare_testdata.py", "reference/make_corpus.py", "reference/minimp3_ref.c")
    inputs.property("command", command)
    outputs.file(testDataDir.map { it.file("manifest.tsv") })
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (!skipTestData) {
        dependsOn(prepareTestData)
    }
    systemProperty("minimp3.testdata", testDataDir.get().asFile.absolutePath)
    systemProperty("minimp3.testdata.optional", skipTestData.toString())
    systemProperty("minimp3.reports", reportsDir.get().asFile.absolutePath)
    // -PdiffFuzz=N: how many corrupted streams DifferentialFuzzTest decodes with both C and Java
    providers.gradleProperty("diffFuzz").orNull?.let { systemProperty("minimp3.difffuzz", it) }
    maxHeapSize = "2g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.test {
    systemProperty("minimp3.report.suffix", "-jdk21")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

val testJdk25 = tasks.register<Test>("testJdk25") {
    group = "verification"
    description = "Runs the tests on JDK 25."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
    systemProperty("minimp3.report.suffix", "-jdk25")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(testJdk25)
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures the decoding speed on the 4-minute benchmark files (not part of check)."
    if (!skipTestData) {
        dependsOn(prepareTestData)
    }
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.radiomod.mp3.Mp3Benchmark"
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("benchmarkJdk").getOrElse("21").toInt())
    }
    args(testDataDir.get().asFile.absolutePath, reportsDir.get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------------------------------
// Publishing. See RELEASING.md.
// ---------------------------------------------------------------------------------------------------
val stagingDir = layout.buildDirectory.dir("staging-deploy")

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "minimp3-java"
            from(components["java"])
            pom {
                name = "minimp3-java"
                description = project.description
                url = providers.gradleProperty("pomUrl")
                licenses {
                    license {
                        name = "CC0-1.0"
                        url = "https://creativecommons.org/publicdomain/zero/1.0/legalcode"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = providers.gradleProperty("pomDeveloperId")
                        name = providers.gradleProperty("pomDeveloperName")
                        url = providers.gradleProperty("pomDeveloperUrl")
                    }
                }
                scm {
                    url = providers.gradleProperty("pomUrl")
                    connection = providers.gradleProperty("pomScmConnection")
                    developerConnection = providers.gradleProperty("pomScmDeveloperConnection")
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(stagingDir)
        }
    }
}

// An ASCII-armored private key and its passphrase, from the environment or from
// ~/.gradle/gradle.properties: never from a file in the repository. Whatever precedes the armor is
// dropped: a byte order mark that PowerShell adds when the key is piped into a secret makes it
// unreadable otherwise.
val signingKey: Provider<String> = providers.environmentVariable("SIGNING_KEY").filter { it.isNotBlank() }
    .orElse(providers.gradleProperty("signingKey"))
    .map { key -> key.indexOf("-----BEGIN").let { if (it > 0) key.substring(it) else key } }

signing {
    val password = providers.environmentVariable("SIGNING_PASSWORD").filter { it.isNotBlank() }
        .orElse(providers.gradleProperty("signingPassword"))
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), password.orNull)
        sign(publishing.publications["maven"])
    }
}

tasks.named("publishMavenPublicationToStagingRepository") {
    doFirst {
        stagingDir.get().asFile.deleteRecursively()
    }
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zips the signed staging repository into a bundle for the Maven Central Portal."
    dependsOn("publishMavenPublicationToStagingRepository")
    from(stagingDir)
    exclude("**/maven-metadata*")
    archiveFileName = "minimp3-java-${project.version}-bundle.zip"
    destinationDirectory = layout.buildDirectory.dir("central")
    val version = project.version.toString()
    val signed = signingKey.isPresent
    doFirst {
        check(!version.endsWith("-SNAPSHOT")) { "Maven Central does not accept SNAPSHOT versions: set version in gradle.properties" }
        check(signed) { "Maven Central requires signed artifacts: set SIGNING_KEY and SIGNING_PASSWORD" }
    }
}
