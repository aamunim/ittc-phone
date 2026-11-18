# ITTC Phone on Google Play

App id: **`com.abamunim.ittcphone`** · name **ITTC Phone** · category **Communication** ·
contact **abamunim@gmail.com** · privacy policy **https://lead.ittc.edu.bd/privacy-ittc-phone**

This folder has the store listing: texts in `en-US/` and `bn-BD/` (title, short and full
description) and `graphics/` (512 × 512 icon, 1024 × 500 feature graphic). Screenshots are taken on
a phone (see 3).

## 1. Create the app (once)
1. Play Console → **Create app** → name *ITTC Phone*, default language English (United States),
   App, Free.
2. Run **Actions → Build → Run workflow** (branch `main`, track `none`) and download the artifact
   **ittc-phone-play** (the `.aab`).
3. **Test and release → Internal testing → Create new release** → upload that `.aab` by hand
   (Google wants the very first one this way). Accept **Play App Signing**.
4. **Test and release → App integrity → App signing**: copy the **SHA-256 of the app signing key
   certificate** and send it to the developer. ITTC Admission Tracker must trust it (it checks
   ITTC Phone's certificate); until then the Play-installed ITTC Phone works as a phone app but shows
   no lead names.
5. **Users and permissions**: give the Play service account (the one in the
   `PLAY_SERVICE_ACCOUNT_JSON` secret) access to *ITTC Phone* (Release manager), and add the same
   secret to this repository (Settings → Secrets → Actions). From then on: Actions → Build → Run
   workflow → track `internal` (status `draft` until the app has been reviewed once).

## 2. App content (Policy → App content)
| Item | Answer |
|---|---|
| Privacy policy | https://lead.ittc.edu.bd/privacy-ittc-phone |
| App access | Most of the app (calls, contacts, recents) needs no sign-in. The lead features need ITTC Admission Tracker signed in: give reviewers a demo staff account of the Tracker in the instructions (never a real one). |
| Ads | No ads |
| Content rating | Questionnaire: Communication app, no violence/sex/gambling, users do not interact with each other through the app → Everyone / 3+ |
| Target audience | 18 and over (institute staff) |
| News app / Health / Financial / Government | No |
| Data safety | **No data collected or shared by the app** (it sends nothing over the internet). Data is processed on the device only. *(Change this when ITTC Phone gets its own sign-in.)* |
| **Sensitive permissions → Call log** | Core use: **Default Phone handler**. ITTC Phone asks to be the default phone app on start (Android's dialer role). Attach a short video: open the app → the disclosure → "Set as default" → a call → Recents. |
| Full-screen intent (Android 14+) | Used for incoming calls (a calling app) |

## 3. Store listing (Grow → Store presence → Main store listing)
- Texts: copy from `en-US/` (and add Bengali – Bangladesh as a translation from `bn-BD/`).
- App icon: `graphics/icon-512.png` · Feature graphic: `graphics/feature-graphic-1024x500.png`.
- Phone screenshots (2–8, portrait), taken on a phone with sample/demo leads only (no real
  students' names or numbers): the Leads tab, the after-call screen, Recents with lead names, the
  dial pad with a lead match, the call screen.

## 4. Testing and going public
- **Internal testing** (up to 100 people, by e-mail list): available in minutes, for the staff.
- A **personal** developer account made after November 2023 must run a **closed test with at least
  12 testers for 14 days in a row** before it can apply for **Production** (public). Use track
  `alpha` (Closed testing) with the staff as testers, then apply for production access.

## 5. Moving staff to the Play version
The Play app has a new app id, so it installs next to the old GitHub one (`org.fossify.phone`).
Install from Play → open it → make it the phone app → uninstall the old ITTC Phone. Calls and
contacts are on the phone, nothing is lost. The Tracker accepts both while people move, and shows
"Get ITTC Phone on Google Play" to anyone still on the old one.
