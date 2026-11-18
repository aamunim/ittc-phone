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
3. **Test and release → Internal testing → Create new release** → before uploading, at **App
   signing** choose **"Change app signing key" → "Use the same key as another app in this
   developer account" → ITTC Admission Tracker** (`com.abamunim.leads`). Then upload that `.aab`
   by hand (Google wants the very first one this way).
   - Why: the Tracker trusts ITTC Phone by its signing certificate. With the Tracker's own Play key
     (`d07ea3e9…4e54`, already trusted by Tracker 2.10.7) the Play ITTC Phone works with the Tracker
     at once.
   - If that choice is not offered: accept Google's new key, then copy **App integrity → App
     signing → SHA-256 of the app signing key certificate** and send it to the developer; it must go
     into a Tracker release before staff move to the Play version (until then the Play ITTC Phone
     works as a phone app but shows no lead names).
4. Done (18 Nov 2026): Google gave ITTC Phone its own app signing key, SHA-256 `88:04:86:94:…:27:3D`
   (Play Console → `…/app/<id>/keymanagement`). Tracker **2.10.8** trusts it: staff move only after
   they have Tracker 2.10.8.
5. **Users and permissions**: give the Play service account (the one in the
   `PLAY_SERVICE_ACCOUNT_JSON` secret) access to *ITTC Phone* (Release manager), and add the same
   secret to this repository (Settings → Secrets → Actions). From then on: Actions → Build → Run
   workflow → track `internal` (status `draft` until the app has been reviewed once).

## 2. App content (Policy → App content)
| Item | Answer |
|---|---|
| Privacy policy | https://lead.ittc.edu.bd/privacy-ittc-phone |
| App access | Calls, contacts and recents need no sign-in. The lead features (Leads tab, lead names, after-call screen, lead page, add lead) need a sign-in: **menu → ITTC account** with an ITTC staff e-mail and password (or ITTC Admission Tracker signed in on the same phone). Give reviewers a **demo staff account** made only for them (a caller with a few sample leads, no real students), in "All or some functionality is restricted" → add the e-mail and password and these steps: open ITTC Phone → agree → menu (⋮) → ITTC account → sign in → Leads tab. Never give a real account. |
| Ads | No ads |
| Content rating | Questionnaire: Communication app, no violence/sex/gambling, users do not interact with each other through the app → Everyone / 3+ |
| Target audience | 18 and over (institute staff) |
| News app / Health / Financial / Government | No |
| Data safety (3.2.0+) | **Data is collected** (only when the person signs in to their ITTC account in ITTC Phone). Declare: **Personal info → Name, Email address** (the staff member's own account; App functionality, Account management) · **Personal info → Phone number** (the leads' numbers on lead calls; App functionality) · **App activity → Other actions** / **Calls → Call logs** (calls with leads only: time, length, direction, SIM name; App functionality) · **App info and performance → Other** none. For each: collected, **not shared**, **processed ephemerally: no**, **required: no (optional sign-in)**, purpose **App functionality**. Security: **data is encrypted in transit: yes** · **users can request deletion: yes** (lead.ittc.edu.bd/delete-account). Without a sign-in nothing leaves the phone, and lead calls handed to ITTC Admission Tracker are declared by that app. |
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

## 5. The order to roll out
**3.2.0 (own sign-in):** 1) database update (App update → Database only) · 2) Tracker 2.11.0 (it
stops sending lead calls when ITTC Phone sends them itself, so nothing is saved twice) · 3) the
`SUPABASE_URL` and `SUPABASE_ANON_KEY` secrets in this repository (the build reads them; without
them the sign-in is hidden) · 4) ITTC Phone 3.2.0 → Internal testing · 5) Data safety and App access
updated as in 2 **before** the release is sent for review (the INTERNET permission and the sign-in
are new).

**Earlier (2.0.0):**
1. **Tracker 2.10.7 first** (it trusts ITTC Phone from Google Play and knows its new app id; an
   older Tracker shows no leads with the Play ITTC Phone).
2. Then ITTC Phone on Internal / Closed testing, with staff added as testers.
3. Then tell staff to move (below).

## 6. Moving staff to the Play version
While the app is on Internal / Closed testing, staff must first accept the testing invitation
(Play Console → the track → Testers → copy the **opt-in link** and send it); the store page opens for
them only after that.

The Play app has a new app id, so it installs next to the old GitHub one (`org.fossify.phone`).
Install from Play → open it → make it the phone app → uninstall the old ITTC Phone. Calls and
contacts are on the phone, nothing is lost. The Tracker accepts both while people move, and shows
"Get ITTC Phone on Google Play" to anyone still on the old one.
