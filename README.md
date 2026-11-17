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
- App name "ITTC Phone". The app id stays `org.fossify.phone` because Fossify's shared library
  checks for it (calls, the phone-app role, app icons). So ITTC Phone and the real Fossify Phone
  cannot be installed together; uninstall Fossify Phone first if it is there.

The two apps talk only through the Tracker's provider; each checks the other's signing
certificate against a fixed list (the Tracker from Google Play carries Google's key). The
contract is in [docs/ITTC.md](docs/ITTC.md).

## Changes from Fossify Phone (GPL-3.0 §5)

- `app/src/main/kotlin/org/fossify/phone/helpers/IttcBridge.kt` (new): lead lookup and the
  call-ended message.
- `services/CallService.kt`, `activities/CallActivity.kt`, `res/layout/activity_call.xml`:
  the hooks for the above and the lead line on the call screen.
- `AndroidManifest.xml`, `gradle.properties`, `app/build.gradle.kts`, `res/values/ittc_strings.xml`:
  name, version, package visibility of the Tracker.
- `.github/workflows/build.yml`: builds the signed APK; Fossify's own workflows removed.

## Build

GitHub Actions builds every push. A tag `v1.2.3` makes a GitHub Release with the APK.
Signing uses the repository secrets `ITTC_KEYSTORE_BASE64`, `ITTC_KEYSTORE_PASSWORD`,
`ITTC_KEY_ALIAS` and `ITTC_KEY_PASSWORD` (the Tracker's key).

Locally: `./gradlew assembleFossRelease` (unsigned without the signing variables).
A debug build (debug key) installs fine but the Tracker does not answer it: its certificate is
not on the Tracker's list.
