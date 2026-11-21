# Releasing to Maven Central

The build uses only Gradle's own `maven-publish` and `signing` plugins. It writes a signed Maven
repository to `build/staging-deploy` and zips it into a bundle for the
[Central Portal](https://central.sonatype.com).

## Once

1. Sign in to the Central Portal with the GitHub account `akivanno` and verify the namespace
   `io.github.akivanno`, the `group` of `gradle.properties` (for `io.github` namespaces the portal checks
   the GitHub account). The groupId is not the Java package name, which stays `com.radiomod.mp3`.
2. Create a GPG key for signing and publish its public part to a key server, for example
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY ID>`.
3. Generate a user token in the Central Portal (Account > Generate User Token).
4. For releases from GitHub Actions, add these secrets to a `maven-central` environment of the
   repository: `SIGNING_KEY` (the output of `gpg --armor --export-secret-keys <KEY ID>`),
   `SIGNING_PASSWORD`, `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`.

Never commit keys or tokens. For local releases put them in environment variables (`SIGNING_KEY`,
`SIGNING_PASSWORD`) or in `~/.gradle/gradle.properties` (`signingKey`, `signingPassword`), outside the
repository.

## Each release

1. Set `version` in `gradle.properties` to the release version (no `-SNAPSHOT`), commit and tag it
   (`git tag v1.0.0`).
2. Either run the **Release to Maven Central** workflow from the Actions tab, or locally:

   ```
   ./gradlew check centralBundle
   ```

   and upload `build/central/minimp3-java-<version>-bundle.zip` in the Central Portal
   (Publish > Publish Component).
3. In the Central Portal, check the validated deployment and press **Publish**.
4. Set `version` to the next `-SNAPSHOT`.

`centralBundle` refuses SNAPSHOT versions and unsigned artifacts. The bundle contains the jar, the
sources jar, the javadoc jar, the POM and the Gradle module metadata, each with its signature and
checksums.
