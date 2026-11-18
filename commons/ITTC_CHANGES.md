# Fossify Commons in ITTC Phone

This folder is **Fossify Commons 6.1.0** (commit `61092edcc8adcdfddbd74b9ca94871b27563a9b1`,
https://github.com/FossifyOrg/Commons), © the Fossify contributors, under the **GNU GPL v3** (see
`LICENSE`). It is kept here, with the small changes below, because the published library assumes the
app id starts with `org.fossify.` — and ITTC Phone needs its own app id (`com.abamunim.ittcphone`) to
be on Google Play.

Every change is marked `🎓 ITTC` in the code:

| File | Change |
|---|---|
| `extensions/Context.kt` | `isDefaultDialer()` checks the real phone-app role for any app id (the library said "yes" for every app not named `org.fossify.phone`/`contacts`). New `appCodePackage()`: the app's code package, from its `package_name` string. |
| `extensions/Activity.kt` | `launchCallIntent` opens this app's own `DialerActivity` (it named `org.fossify.phone`). Launcher-icon colour classes use `appCodePackage()`. |
| `compose/extensions/ActivityExtensions.kt`, `extensions/Context-styling.kt` | The same for the launcher-icon colours; no "fake version" warning. |
| `activities/BaseSimpleActivity.kt` | No "You are using a fake version… download the original from fossify.org" warning, and Customize colours is not blocked, for an app id outside `org.fossify`. |
| `activities/ManageBlockedNumbersActivity.kt`, `helpers/MyContactsContentProvider.kt` | "Is this the phone app" checks use `appCodePackage()`, not the app id (blocking calls, private contacts). |
| `activities/CustomizationActivity.kt` | The primary colour picker is not closed for an app id outside `org.fossify`. |
| `activities/BaseSimpleActivity.kt` (2) | `launchSetDefaultDialerIntent()` is public (ITTC Phone's Recents asks for the phone-app role after its disclosure). |
| `build.gradle.kts` | Built as a module of this project (its own version list `gradle/commons.versions.toml`, shown as `clibs`); no publishing, no detekt. |

Left as they are (not reached by ITTC Phone, harmless): the SAF dialog checks in
`BaseSimpleActivity` (skipped for ids outside `org.fossify`), the settings-export file name (uses the
app id), and the e-mail choice in commons' `AboutActivity` (ITTC Phone has its own About).

Nothing else is changed. To move to a newer Commons: copy its `commons/` folder over this one, then
redo the changes above (search the old copy for `🎓 ITTC`).
