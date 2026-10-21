# Publishing to the Gradle Plugin Portal

How to release `com.pgyer.upload` to <https://plugins.gradle.org>. Sections 1 to 5 are one-time setup
that only the PGYER owner can do. Section 6 is the routine for each release.

What gets published, by the `com.gradle.plugin-publish` plugin (2.2.1, configured in
`build.gradle.kts`):

| Artifact | Coordinates |
| --- | --- |
| Plugin jar, with sources and Javadoc jars | `com.pgyer:pgyer-gradle-plugin:<version>` |
| Plugin marker, which resolves `id("com.pgyer.upload")` | `com.pgyer.upload:com.pgyer.upload.gradle.plugin:<version>` |

The plugin declares that it supports the configuration cache. The descriptor carries
`compatibility.feature.configuration-cache=DECLARED_SUPPORTED`. Once a version declares a feature,
later versions must keep declaring it.

## 1. Before the first publish: make the repository public

Reviewers open the `website` and `vcsUrl` links (both point to
`https://github.com/PGYER/pgyer-gradle-plugin`) and reject a plugin whose links are broken or
private, or whose README is empty or not in English. As of 2026-10-21 that repository doesn't
exist, or isn't public: the GitHub API answers 404.

1. Create `PGYER/pgyer-gradle-plugin` on GitHub as a **public** repository and push `main`.
2. Check that the CI workflow (`.github/workflows/ci.yml`) passes there. It hasn't run on GitHub yet.
3. If the repository ends up at another URL, update `website` and `vcsUrl` in `build.gradle.kts`.

## 2. Create the Plugin Portal account

1. Go to <https://plugins.gradle.org> and choose **Login**. Sign-in now goes through a Gradle /
   Develocity account at `identity.gradle.com`. You can sign up with an email address, or with
   GitHub, Google, LinkedIn or Microsoft.
2. **Use a corporate `@pgyer.com` email address**, preferably a group mailbox such as
   `dev@pgyer.com` rather than one person's address. The portal rules say that for a domain-based
   ID like `com.mybusiness.pluginname`, *"the author should also submit the plugin via a corporate
   account/email address. We prefer group (as opposed to individual) addresses"*
   (<https://plugins.gradle.org/docs/publish-plugin>).
3. Make sure several PGYER people can reach that mailbox. Approval mail, and any request for
   proof of domain ownership, goes there.

## 3. Create API keys and store them as secrets

1. On the portal, open your profile page and the **API Keys** tab. Generate a key and copy the
   **key** and the **secret**.
2. Store them for the machine or CI job that publishes. Never commit them.
   - **GitHub Actions** (recommended): add repository secrets `GRADLE_PUBLISH_KEY` and
     `GRADLE_PUBLISH_SECRET` (*Settings > Secrets and variables > Actions*). The plugin-publish
     plugin reads these environment variables directly.
   - **A developer machine**: add them to `~/.gradle/gradle.properties`, outside the repository:

     ```properties
     gradle.publish.key=...
     gradle.publish.secret=...
     ```

     You can also pass them once as `-Pgradle.publish.key=... -Pgradle.publish.secret=...`, or run
     `./gradlew login`, which writes them to that file for you.
3. Whoever holds the keys can publish new versions under `com.pgyer`. Rotate them on the API Keys
   tab if they leak.

## 4. First publish and manual approval

1. Dry run, which checks the metadata with the portal without publishing anything:

   ```shell
   ./gradlew publishPlugins --validate-only
   ```

2. Publish:

   ```shell
   ./gradlew publishPlugins
   ```

   A new plugin isn't public straight away. The task reports that the plugin is **pending
   approval**, and *"a Gradle engineer will inspect the metadata"*. The portal emails the account
   address to ask for changes or to confirm acceptance. Expect a few days. Reports from other
   publishers range from one day to more than a week.

3. What reviewers check, according to the portal rules:
   - The plugin does something real: no "hello world" plugins.
   - It is useful to a wide audience. *"Plugins that are too specific to a particular use case or
     company will be rejected."* This is the main risk for a plugin tied to one service. In the
     approval conversation, explain that it serves every Android, iOS and HarmonyOS team that
     distributes builds through PGYER, a public platform. A community plugin for the same service,
     `io.github.5peak2me.gradle.pgyer`, is already on the portal.
   - It has a description and tags. The website points to English documentation, and the VCS URL
     points to public sources. Open source is preferred.
   - The IDs trace back to the author. The group and plugin ID share a prefix: here `com.pgyer` and
     `com.pgyer.upload`, which meets this rule.
   - Versions are final: no `-SNAPSHOT` versions, which the client and portal both reject.
   - The repository is not a fork.

## 5. Prove ownership of pgyer.com (if the reviewers ask)

`com.pgyer` is a domain-based namespace, so the reviewers may ask PGYER to prove that it owns
`pgyer.com`. The documented method is a DNS TXT record. From the portal docs:

> We will ask for proof of the domain ownership in the form of a TXT record added to the DNS entry.

> you might be asked to prove ownership of the organization's domain by adding some random TXT
> DNS record ... Portal administrators will contact you and provide you with the exact record.

What the owner does:

1. Wait for the email from the portal administrators. It names the exact TXT record (host and
   value). Don't guess the record in advance.
2. In the DNS console that hosts `pgyer.com` (for example DNSPod / Tencent Cloud or Alibaba Cloud
   DNS), add that TXT record exactly as given.
3. Check that it is visible from outside, for example:

   ```shell
   dig +short TXT pgyer.com
   dig +short TXT <the host they gave>.pgyer.com
   ```

4. Reply to the email that the record is in place, and leave it there until the plugin is
   approved.

The docs describe no other way to verify a domain, such as a file on the website or a GitHub
organization check. Signing up with an `@pgyer.com` address (step 2) is the other signal the
reviewers look at. This differs from `io.github.<user>` IDs, which are verified by linking the
portal account to that GitHub account.

## 6. Every release

1. Set the version in `gradle.properties` (`version=1.0.1`). Versions are immutable: a published
   version can never be republished, only deleted (see below).
2. Update the version in `README.md` examples.
3. Run the full test suite on Java 17 and 21 with an Android SDK, or let CI do it:

   ```shell
   ./gradlew check -PtestBuildToolsVersion=36.1.0
   ```

4. Commit, tag (`git tag v1.0.1`) and push.
5. Publish: `./gradlew publishPlugins`. After the first approval, new versions go live without
   review. A change of the Maven group or the plugin ID starts the manual review again.
6. Check <https://plugins.gradle.org/plugin/com.pgyer.upload>, then try the new version in a
   sample project.

### Publishing from GitHub Actions (optional)

Here is a workflow that publishes when a `v*` tag is pushed. Add it as
`.github/workflows/publish.yml` only once the plugin is approved and the secrets from step 3
exist:

```yaml
name: Publish
on:
  push:
    tags: ['v*']
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew check publishPlugins -PtestBuildToolsVersion=36.1.0
        env:
          GRADLE_PUBLISH_KEY: ${{ secrets.GRADLE_PUBLISH_KEY }}
          GRADLE_PUBLISH_SECRET: ${{ secrets.GRADLE_PUBLISH_SECRET }}
```

### Signing (optional)

The portal doesn't require signed artifacts. If you apply the `signing` plugin and configure a
key, `plugin-publish` signs the publications automatically.

### Deleting a broken version

You can delete a version from the plugin's page on the portal *"within 7 days of the version being
published or approved, whichever is later"*. A version still pending approval can be deleted at
any time. After that, ask the portal team through <https://plugins.gradle.org/docs/get-help> or
<https://github.com/gradle/plugin-portal-requests>. A deleted version number can't be reused, so
release a fix as a new version.

## References

- Portal publishing guide and rules: <https://plugins.gradle.org/docs/publish-plugin>
- Gradle user guide, *Publishing Plugins to the Gradle Plugin Portal*:
  <https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html>
- Deleting versions: <https://plugins.gradle.org/docs/deleting>
- Terms of use: <https://plugins.gradle.org/docs/terms>
- `com.gradle.plugin-publish`: <https://plugins.gradle.org/plugin/com.gradle.plugin-publish>
