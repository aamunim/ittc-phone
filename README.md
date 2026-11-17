# ITTC Phone

The phone app (dialer) of the ITTC Admission Tracker.

ITTC Phone is a modified version of **[Fossify Phone](https://github.com/FossifyOrg/Phone)**
(version 1.11.1), released under the **GNU General Public License v3.0** like the original —
see [LICENSE](LICENSE). All credit for the phone app itself goes to the Fossify contributors.

## What ITTC Phone adds

- 🎓 **The lead on the call screen** — when the number belongs to a lead in the ITTC
  Admission Tracker, the call screen shows the lead's name, course, stage and last note.
- 📞 **Lead calls reach the Tracker the moment they end** — ITTC Phone tells the Tracker which
  row of the phone's call log the call is; the Tracker reads that row itself. Only lead calls
  are handed over; personal calls are never sent anywhere.
- App name "ITTC Phone" and its own app id (`bd.edu.ittc.phone`), so it installs next to
  Fossify Phone.

The two apps talk only through Android, protected by a signature permission: they must be
signed with the same key. The contract is in [docs/ITTC.md](docs/ITTC.md).

## Changes from Fossify Phone (GPL-3.0 §5)

- `app/src/main/kotlin/org/fossify/phone/helpers/IttcBridge.kt` (new): lead lookup and the
  call-ended message.
- `services/CallService.kt`, `activities/CallActivity.kt`, `res/layout/activity_call.xml`:
  the hooks for the above and the lead line on the call screen.
- `AndroidManifest.xml`, `gradle.properties`, `app/build.gradle.kts`, `res/values/ittc_strings.xml`:
  name, app id, version, the shared permission.
- `.github/workflows/build.yml`: builds the signed APK; Fossify's own workflows removed.

## Build

GitHub Actions builds every push. A tag `v1.2.3` makes a GitHub Release with the APK.
Signing uses the repository secrets `ITTC_KEYSTORE_BASE64`, `ITTC_KEYSTORE_PASSWORD`,
`ITTC_KEY_ALIAS` and `ITTC_KEY_PASSWORD` (the Tracker's key).

Locally: `./gradlew assembleFossRelease` (unsigned without the signing variables).
