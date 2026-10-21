# Upload to PGYER — Gradle plugin

The official Gradle plugin of [PGYER](https://www.pgyer.com) (蒲公英), the app beta-distribution platform.
It uploads Android APKs straight from your build, and any `.apk`, `.ipa` or `.hap` file you point it
at, then prints the download page and QR code your testers need.

- One `uploadPgyer<Variant>` task per Android application variant (`uploadPgyerRelease`,
  `uploadPgyerFreeDebug`, ...). It builds the APK first.
- A plain `PgyerUploadTask` for prebuilt files, iOS builds (for example from Kotlin Multiplatform)
  and HarmonyOS apps. It does not need the Android Gradle Plugin.
- Works with the configuration cache. Upload tasks never count as up to date and are never cached.
- The API key and install password are never logged and are not task inputs.
- Falls back to PGYER's backup domains when `www.pgyer.com` can't be reached, and waits until PGYER
  has finished processing the build.

[中文说明](#中文说明)

## Requirements

| | Minimum | Tested with |
| --- | --- | --- |
| Gradle | 8.0 | 8.0.2, 8.5, 8.14.6, 9.8.1 |
| Android Gradle Plugin (for variant tasks) | 8.0 | 8.0.2, 8.13.2, 9.4.1 |
| Java running Gradle | 17 | 17, 21 |

You need a PGYER account and its API key from <https://www.pgyer.com/account/api>.

## Quick start

1. Add the plugin to your Android app module (`app/build.gradle.kts`):

   ```kotlin
   plugins {
       id("com.android.application")
       id("com.pgyer.upload") version "1.0.0"
   }

   pgyer {
       updateDescription = "Fixed the login crash"
   }
   ```

   If your root `build.gradle.kts` declares plugins with `apply false` (common with version
   catalogs), declare `com.pgyer.upload` there too, next to `com.android.application`, and apply it
   in the app module without a version. See [Troubleshooting](#troubleshooting) for why.

2. Give the plugin your API key without committing it, either in `~/.gradle/gradle.properties`:

   ```properties
   pgyer.apiKey=your-api-key
   ```

   or as an environment variable, the usual choice on CI:

   ```shell
   export PGYER_API_KEY=your-api-key
   ```

3. Build and upload:

   ```shell
   ./gradlew uploadPgyerRelease
   ```

   ```text
   > Task :app:uploadPgyerRelease
   [PGYER] Uploading app-release.apk (12.4 MB)...
   [PGYER] Uploaded 10% (1.2 MB)
   ...
   [PGYER] Upload finished. Waiting for PGYER to process the build...
   [PGYER] Published My App 1.2.0 (build 7)
   [PGYER] Download page: https://www.pgyer.com/myapp
   [PGYER] QR code: https://www.pgyer.com/app/qrcodeHistory/...
   ```

### Groovy DSL

```groovy
plugins {
    id 'com.android.application'
    id 'com.pgyer.upload' version '1.0.0'
}

pgyer {
    updateDescription = 'Fixed the login crash'
    installType = 'password'
    password = providers.environmentVariable('PGYER_INSTALL_PASSWORD')
}
```

## Tasks

### `uploadPgyer<Variant>` (Android)

With `com.android.application` applied, the plugin creates one task of type
`com.pgyer.upload.PgyerAndroidUploadTask` per variant. The task name is `uploadPgyer` plus the
capitalized variant name, for example `uploadPgyerRelease`, `uploadPgyerDebug` or
`uploadPgyerStagingRelease`. The task takes the variant's APK through the AGP Variant API, so Gradle
builds the APK first. List them with `./gradlew tasks --group pgyer`.

If a variant uses ABI or density splits, the task uploads the universal APK. Turn it on with
`splits { abi { isUniversalApk = true } }`, or upload one split APK with a `PgyerUploadTask`.
The variant tasks upload APKs, not App Bundles (`.aab`).

### `PgyerUploadTask` (any file)

Register one for any `.apk`, `.ipa` or `.hap` file. The build type is taken from the extension.

```kotlin
// Kotlin DSL
tasks.register<com.pgyer.upload.PgyerUploadTask>("uploadIpa") {
    file = layout.buildDirectory.file("ios/MyApp.ipa")
    updateDescription = "iOS nightly"
}

tasks.register<com.pgyer.upload.PgyerUploadTask>("uploadHap") {
    file = layout.projectDirectory.file("entry/build/default/outputs/default/entry-default-signed.hap")
}
```

```groovy
// Groovy DSL
tasks.register('uploadIpa', com.pgyer.upload.PgyerUploadTask) {
    file = layout.buildDirectory.file('ios/MyApp.ipa')
    dependsOn 'buildIpa'   // whatever task produces the file, if it isn't wired through `file`
}
```

If `file` is set from another task's output, Gradle runs that task first. Settings you don't set on
the task come from the `pgyer { }` extension.

## Options

Set these in `pgyer { }`, for one variant in `pgyer { variants { ... } }`, or on a task. All of
them are lazy properties, so they also take providers such as
`providers.environmentVariable("NOTES")`. Settings you don't set are not sent, so PGYER keeps the
app's current settings for them.

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `apiKey` | `String` | Gradle property `pgyer.apiKey`, then env `PGYER_API_KEY` | Your PGYER API key. Required. It is never logged and is not a task input. |
| `updateDescription` | `String` | – | What changed in this build. Shown on the download page. |
| `description` | `String` | – | The app description. On a task this is `appDescription`, because `description` is the task's own description. |
| `installType` | `String` | – | Who can install: `1` or `public`, `2` or `password`, `3` or `invite`. When only `password` is set, the install type is `2`. |
| `password` | `String` | – | The install password. Never logged, not a task input. |
| `installStartDate` | `String` | – | First day the build can be installed, `yyyy-MM-dd`. Set it together with `installEndDate`. |
| `installEndDate` | `String` | – | Last day the build can be installed, `yyyy-MM-dd`. |
| `channelShortcut` | `String` | – | The short link of the download channel to publish to. |
| `oversea` | `Boolean` | – | `true` stores the build overseas, `false` in mainland China. Unset, PGYER chooses by the uploading machine's IP address. |
| `processingTimeout` | `Int` | `600` | Seconds to wait for PGYER to process the build after the upload. |
| `downloadFileName` | `String` | Name of the uploaded file | The file name testers get when they download, including the extension. Plain ASCII only. |

Only in the extension:

| Option | Description |
| --- | --- |
| `variantFilter { }` | Creates `uploadPgyer<Variant>` tasks only for matching variants. The lambda gets a `PgyerVariant` with `name`, `buildType`, `flavorName` and `productFlavors`. By default every variant gets a task. |
| `variants { }` | Settings for one variant, by variant name. `enabled = false` creates no task for that variant. Any option above overrides the extension's value. |

Only on tasks:

| Option | Description |
| --- | --- |
| `file` | `PgyerUploadTask` only. The file to upload. |
| `resultFile` | Where the build information is written. See [Outputs](#outputs). |

### Choosing variants

```kotlin
pgyer {
    updateDescription = "Weekly beta"

    // Only release builds get an upload task.
    variantFilter { it.buildType == "release" }

    variants {
        create("paidRelease") {
            enabled = false
        }
        create("freeRelease") {
            channelShortcut = "free-beta"
            installType = "password"
            password = providers.environmentVariable("FREE_BETA_PASSWORD")
        }
    }
}
```

In Groovy, a variant block is just its name: `variants { freeRelease { channelShortcut = 'free-beta' } }`.
If a name under `variants` doesn't match any variant, the plugin prints a warning listing the
variants that exist.

To configure all PGYER tasks at once, use the task types:

```kotlin
tasks.withType<com.pgyer.upload.AbstractPgyerUploadTask>().configureEach {
    processingTimeout = 900
}
```

## Outputs

After a successful upload, the task:

- prints the app name, version, download page and QR code link at the default (lifecycle) log
  level. Add `--info` to see the settings that were sent and each status check.
- writes the build information as JSON to `build/outputs/pgyer/<variant>.json` for variant tasks,
  or `build/outputs/pgyer/<task name>.json` for a `PgyerUploadTask`.

The JSON has every field of PGYER's
[`buildInfo`](https://www.pgyer.com/doc/view/api) answer (`buildKey`, `buildName`,
`buildVersion`, `buildBuildVersion`, `buildShortcutUrl`, `buildQRCodeURL`, `buildCreated`, ...), plus
three links:

| Field | Value |
| --- | --- |
| `appPgyerURL` | The download page, `https://www.pgyer.com/<buildShortcutUrl>`. It always shows the app's latest build. |
| `appBuildURL` | The page of this exact build, `https://www.pgyer.com/<buildKey>`. |
| `appQRCodeURL` | The QR code image for the download page (same as `buildQRCodeURL`). |

```shell
jq -r .appPgyerURL app/build/outputs/pgyer/release.json
```

## CI

Keep the API key in your CI's secret store and pass it as `PGYER_API_KEY`. Never write it into
`build.gradle(.kts)` or a committed `gradle.properties`.

**GitHub Actions**

```yaml
- name: Upload to PGYER
  run: ./gradlew :app:uploadPgyerRelease -PpgyerNotes="${{ github.event.head_commit.message }}"
  env:
    PGYER_API_KEY: ${{ secrets.PGYER_API_KEY }}
- name: Show the download page
  run: jq -r .appPgyerURL app/build/outputs/pgyer/release.json
```

To use the commit message as release notes, read it lazily in the build script:

```kotlin
pgyer {
    updateDescription = providers.gradleProperty("pgyerNotes").orElse("Built by CI")
}
```

**GitLab CI**: add `PGYER_API_KEY` as a masked variable under *Settings > CI/CD > Variables*, then
run `./gradlew :app:uploadPgyerRelease`.

**Jenkins**: store the key as a *Secret text* credential and run

```groovy
withCredentials([string(credentialsId: 'pgyer-api-key', variable: 'PGYER_API_KEY')]) {
    sh './gradlew :app:uploadPgyerRelease'
}
```

(The [Upload to PGYER Jenkins plugin](https://plugins.jenkins.io/upload-pgyer/) is an alternative
when Jenkins builds the app.)

## Proxies and timeouts

Uploads use the JVM's standard proxy settings, which you can set in `gradle.properties`:

```properties
systemProp.https.proxyHost=proxy.example.com
systemProp.https.proxyPort=8080
systemProp.https.proxyUser=user
systemProp.https.proxyPassword=secret
systemProp.http.nonProxyHosts=localhost|*.example.com
```

The JDK doesn't send Basic credentials to HTTPS proxies by default. If your proxy needs them, also
set `systemProp.jdk.http.auth.tunneling.disabledSchemes=` (empty).

`processingTimeout` limits the wait after the upload. To limit the whole task, including the upload
itself, use Gradle's task timeout:

```kotlin
tasks.withType<com.pgyer.upload.AbstractPgyerUploadTask>().configureEach {
    timeout = java.time.Duration.ofMinutes(20)
}
```

## Troubleshooting

| Message | What to do |
| --- | --- |
| `No PGYER API key.` | Set the `PGYER_API_KEY` environment variable, `pgyer.apiKey` in `~/.gradle/gradle.properties`, or `pgyer { apiKey = ... }`. |
| `PGYER API error 1001` / `1002` | The API key is wrong. Copy it again from <https://www.pgyer.com/account/api>. |
| `PGYER API error 1003` | Sub-accounts can't use the API. Use the main account's API key. |
| `PGYER API error 1019` | Complete real-name verification first: <https://www.pgyer.com/account/certification>. |
| `PGYER API error 1020` | Uploading is disabled for the account. Contact PGYER customer service. |
| `PGYER API error 1022` | The account's storage is full or the file is too large. Free up space or upgrade the plan. |
| `<server> is not reachable ..., trying ...` | The plugin moves on to PGYER's backup domains (`www.xcxwo.com`, `www.pgyerapp.com`). This happens when DNS for `www.pgyer.com` is blocked or a proxy or captive portal answers instead. |
| `None of the PGYER API servers could be reached` | Check the machine's network and proxy settings (see above). |
| `PGYER is still processing the build after 600 seconds` | The build may still be published, so check your app list. Raise `processingTimeout` for very large apps. |
| `Uploading the file to storage failed with HTTP 403` | The upload took too long and the upload token expired, or the clock is far off. Retry. |
| `Cannot tell the app type of ...` | Only `.apk`, `.ipa` and `.hap` files can be uploaded. |
| `The <variant> variant produces N APKs` | The variant uses splits. Enable a universal APK, or upload one APK with a `PgyerUploadTask`. |
| `The com.pgyer.upload plugin cannot see the classes of the Android Gradle Plugin` | `com.pgyer.upload` is declared in a parent project's build script while AGP is declared only in a subproject. Declare both in the same script (usually the root one, with `apply false`). |
| `Unresolved reference` / type mismatch on `apiKey = "..."` (Kotlin DSL) | Gradle 8.0 and 8.1 don't support `=` for lazy properties. Use `apiKey.set("...")` or upgrade Gradle. |
| `downloadFileName is ignored` | The name must be plain ASCII without `/` or `\`. |

Run with `--info` to see the settings sent (secrets masked) and each processing check, and with
`--stacktrace` for the cause of a failure.

## How it works

The plugin uses the [PGYER API v2](https://www.pgyer.com/doc/view/api#fastUploadApp) in the same way
as PGYER's other official integrations:

1. `POST /apiv2/app/getCOSToken` with the API key, the build type (`android` for `.apk`, `ios` for
   `.ipa`, `harmony` for `.hap`) and the settings you set.
2. Upload the file to the storage endpoint PGYER returns. The file is streamed from disk, never
   loaded into memory.
3. `POST /apiv2/app/buildInfo` every 3 seconds until PGYER has processed the build. Network errors
   while polling are retried until `processingTimeout`.

The API key is only ever sent in POST bodies, never in URLs or headers. The User-Agent is
`pgyer-gradle-plugin/<version> Gradle/<version> Java/<version>`.

## Development

```shell
./gradlew check
```

runs the unit tests and the TestKit functional tests. The functional tests publish the plugin to
`build/functional-test-repo` and resolve it from there, as a real build resolves it from the Plugin
Portal. The Android tests need an Android SDK with platform `android-35` (`ANDROID_HOME`, or
`-PandroidSdk=/path/to/sdk`) and are skipped without one. Useful options:

| Property | Example | Effect |
| --- | --- | --- |
| `-PtestGradleVersions` | `8.0.2,9.8.1` | Gradle versions for the plain-task tests. Default: the current one plus the oldest that runs on your JDK (8.0.2 on Java 17, 8.5 on Java 21). |
| `-PtestAgpVersions` | `8.13.2:8.14.6,9.4.1` | `agp:gradle` pairs for the Android tests. Default: 8.0.2 with Gradle 8.0.2 (Java 17 only), 8.13.2 with 8.14.6, and 9.4.1 with the current Gradle. |
| `-PtestBuildToolsVersion` | `36.1.0` | Build tools for the test app. AGP 8.0 and 8.1 also use this aapt2, because their own aapt2 can't read `android-35`. |

See [PUBLISHING.md](PUBLISHING.md) for releasing to the Gradle Plugin Portal.

## License

[MIT](LICENSE)

---

## 中文说明

蒲公英官方 Gradle 插件（插件 ID `com.pgyer.upload`），在 Gradle 构建中直接把应用上传到蒲公英。

**功能**

- 为每个 Android 应用变体创建 `uploadPgyer<变体名>` 任务（如 `uploadPgyerRelease`），会先构建 APK 再上传。
- `PgyerUploadTask` 可上传任意 `.apk`、`.ipa`、`.hap` 文件，不依赖 Android Gradle Plugin，适合 iOS（如 KMP）、鸿蒙和已构建好的安装包。
- 支持 Gradle 8.0+、AGP 8.0+、配置缓存（configuration cache）。上传任务每次都会执行，不会被缓存。
- API Key 和安装密码不会出现在日志中。`www.pgyer.com` 无法访问时自动切换到备用域名。

**使用**

```kotlin
plugins {
    id("com.android.application")
    id("com.pgyer.upload") version "1.0.0"
}

pgyer {
    updateDescription = "修复了登录崩溃"   // 更新说明
    installType = "password"            // 1/public 公开，2/password 密码安装，3/invite 邀请安装
    password = providers.environmentVariable("PGYER_INSTALL_PASSWORD")
    // oversea = false                  // true 海外存储，false 国内存储，不设置则按 IP 自动选择
    variantFilter { it.buildType == "release" }   // 只为 release 变体创建上传任务
}
```

API Key 在 <https://www.pgyer.com/account/api> 获取。请不要写进代码仓库，推荐：

- 本机：在 `~/.gradle/gradle.properties` 中写 `pgyer.apiKey=你的 API Key`
- CI：设置环境变量 `PGYER_API_KEY`（使用 CI 的密钥/机密变量功能）

然后执行：

```shell
./gradlew uploadPgyerRelease
```

上传成功后日志会显示下载页地址和二维码地址，构建信息会写入 `build/outputs/pgyer/<变体名>.json`（字段 `appPgyerURL` 为下载页地址）。

只设置了 `password` 时，安装方式自动为密码安装（2）。未设置的选项不会发送，蒲公英会保留应用当前的设置。常见错误码：1001/1002 API Key 错误，1003 子账号不能使用 API，1019 需要实名认证，1020 账号被禁止上传，1022 存储空间不足。其余选项和排错说明见上文英文部分。
