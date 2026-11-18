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
- 📝 **After a lead call** — a screen with the lead and how the call went (talked 2m 34s / no answer /
  missed). Calls started from the Tracker skip it: the Tracker shows its own popup.
- 🕘 **Recents** — lead numbers show the lead's name and "🎓 Lead · course · stage".
- 🔢 **Dial pad** — a full number that is a lead's shows who it is above the keys (tap: the lead).
- ⚡ **Quick open** — Recents shows the list from last time at once while the call log is read
  again; the caller's name comes from Android's own number lookup (no waiting for every contact).
- 🎓 **Leads only** filter in Recents; the lead line is coloured by stage; a lead not in the phone's
  contacts keeps the number as the title with "🎓 name · course · stage" small below.
- 📊 Today's calls and talk time on the after-call screen; a smoother dial pad.
- 🎓 **Leads tab** (Tracker 2.10.5+) — your own open leads (≤ 500): today / overdue / new / hot /
  all, 📞 at once, WhatsApp / SMS / the lead in the Tracker; toolbar search by name or number.
- 📝 **Result after a call** (Tracker 2.10.5+) — what happened, a note and the next call, saved
  through the Tracker (it sends it); an older Tracker gets its own note popup instead.
- 🔢 **Dial pad name search** — your leads whose name (by the keys) or number matches.
- ⚡ **Live Leads tab** (Tracker 2.10.6+) — a lead assigned, moved or rescheduled shows in seconds
  while the Tracker runs; "⟳ Updated 2 min ago" (tap: update now), or a warning when the Tracker
  is closed. A row of **pipeline stage** chips.
- 👥 **Super admins** see everyone's open leads (≤ 2000) and pick whose in one chip: All / My /
  Team Leads / Callers (all of them, or one person).
- 🧭 **Stage after a call** (Tracker 2.10.6+) — change the pipeline stage with the result; a stage
  that needs a note or a date asks for it.
- 🎨 **ITTC look** (1.5.0) — the ITTC logo as the app icon (with a small green phone badge; a
  monochrome one for themed icons), ITTC blue as the default colour, the icon on the Android 12+
  opening screen, and ITTC Phone's own About screen (version, new version, the Tracker, privacy,
  licence, source code, libraries). Fossify's "More apps" menu item and its About screen (its
  links, donation and social pages) are gone. Kept, as the GNU GPL requires: the licence, the
  copyright notices in the source, and a credit line "based on Fossify Phone" in About.
- App name "ITTC Phone", app id **`com.abamunim.ittcphone`** (2.0.0, for Google Play; the code stays
  in `org.fossify.phone`). Fossify Commons is kept in `commons/` with small changes so it works under
  another app id (see `commons/ITTC_CHANGES.md`). Older GitHub builds used `org.fossify.phone`.

The two apps talk only through the Tracker's provider; each checks the other's signing
certificate against a fixed list (the Tracker from Google Play carries Google's key). The
contract is in [docs/ITTC.md](docs/ITTC.md).

## Changes from Fossify Phone (GPL-3.0 §5)

- `app/src/main/kotlin/org/fossify/phone/helpers/IttcBridge.kt` (new): lead lookup, the
  call-ended message, Recents lookups, opening a lead in the Tracker.
- `activities/IttcAfterCallActivity.kt`, `res/layout/ittc_activity_after_call.xml` (new): the
  after-call screen.
- `services/CallService.kt`, `activities/CallActivity.kt`, `res/layout/activity_call.xml`:
  the hooks for the above and the lead line on the call screen.
- `adapters/RecentCallsAdapter.kt`, `res/layout/item_recent_call.xml`: the lead line in Recents.
- `activities/DialpadActivity.kt`, `res/layout/activity_dialpad.xml`: the lead above the dial pad.
- `fragments/IttcLeadsFragment.kt`, `res/layout/ittc_fragment_leads.xml`, `ittc_item_lead.xml`,
  `adapters/ViewPagerAdapter.kt`, `activities/MainActivity.kt`: the Leads tab.
- `fragments/RecentsFragment.kt`, `helpers/IttcRecentsCache.kt`, `helpers/CallContactHelper.kt`:
  quick Recents and caller names.
- `AndroidManifest.xml`, `gradle.properties`, `app/build.gradle.kts`, `res/values/ittc_strings.xml`:
  name, version, package visibility of the Tracker.
- `.github/workflows/build.yml`: builds the signed APK; Fossify's own workflows removed.

## Build

GitHub Actions builds every push (a test APK and the Play bundle `.aab` as artifacts). To send it
to Google Play: Actions → Build → Run workflow (`main`) → track `internal` / `alpha` /
`production`. See `play/README.md` for the Play Console steps and the store listing. GitHub
Releases with an APK are no longer made.
Signing uses the repository secrets `ITTC_KEYSTORE_BASE64`, `ITTC_KEYSTORE_PASSWORD`,
`ITTC_KEY_ALIAS` and `ITTC_KEY_PASSWORD` (the Tracker's key).

Locally: `./gradlew assembleFossRelease` (unsigned without the signing variables).
A debug build (debug key) installs fine but the Tracker does not answer it: its certificate is
not on the Tracker's list.
