# 📞 ITTC Phone ↔ ITTC Admission Tracker

ITTC Phone is a separate app (made from Fossify Phone, GPL-3.0, source at
github.com/aamunim/ittc-phone). Its app id is `com.abamunim.ittcphone` (Google
Play, 2.0.0+); older GitHub builds used `org.fossify.phone`, still accepted while people move. This app stays private; the two only talk through the provider below.

## Who may talk to whom: certificate lists

No shared permission. This app comes from Google Play, re-signed with Google's key (Play App
Signing), which ITTC Phone can never carry; two apps declaring one permission with different
keys cannot even be installed together. Instead each side checks the other's signing
certificate (SHA-256) against a fixed list, on every request:

| app | certificates it is accepted with |
|---|---|
| ITTC Phone (`com.abamunim.ittcphone`; old: `org.fossify.phone`) | upload key `2955a704…0d83` (GitHub Actions), and Google Play's app signing key for ITTC Phone `88048694…273d` (Tracker 2.10.8+) |
| Tracker (`com.abamunim.leads`) | Google Play app signing key `d07ea3e9…4e54`, upload key `2955a704…0d83` (APK builds) |

The lists live in `IttcPhone.kt` (here) and `IttcBridge.kt` (ITTC Phone). A new signing key
means adding its SHA-256 on both sides.

## Android 11+ package visibility

Each app lists the other in `<queries>` (this one: `com.abamunim.ittcphone` and `org.fossify.phone`; ITTC Phone:
`com.abamunim.leads`).
ITTC Phone 3.15+ also lists WhatsApp (`com.whatsapp`), WhatsApp Business (`com.whatsapp.w4b`) and
the apps that open `whatsapp://` links (clones / other WhatsApp apps), for its "which WhatsApp" choice.

## 1. Is this number a lead? (ITTC Phone → this app)

    query content://com.abamunim.leads.leadlookup/number/<Uri.encode(number)>

One row or none, columns:
`lead_id, name, rating, stage, course, last_note, student_ref, fee_due, next_call, owner, app_call`.
One number at a time; the list itself is never handed out. Query off the main thread.

`app_call` (2.10.3+) is `"1"` when this app's Call button started a call to that number: then
ITTC Phone shows no after-call screen (this app shows its own popup when the call ends). Asked
after the call, add `?started=<ms>` (when the call began) to the URI.

### Many numbers at once (Recents, 2.10.3+)

    query content://com.abamunim.leads.leadlookup/numbers, selectionArgs = the numbers (≤ 300)

A row for each number that is a lead: column `number` (exactly as sent) and the columns above
(without `app_call`). Only numbers ITTC Phone already has in its call log are sent.

## 2. A call ended (ITTC Phone → this app)

    contentResolver.call(Uri.parse("content://com.abamunim.leads.leadlookup"),
                         "call_ended", <call log _ID>, Bundle{ number, end_reason, shows_note })

- `arg`: the system call log's `_ID` for the call, read *after* the system wrote the row.
- `number`: only used to check that the row is that call.
- `end_reason` (optional): `hung_up`, `they_hung_up`, `busy`, `missed`, `declined`, `cancelled`,
  `error`, `restricted`, `answered_elsewhere`, `pulled` (letters, digits, `_`).
- `shows_note` (optional, 2.10.3+): `"1"` when ITTC Phone shows its after-call screen (with
  "Add a note"); this app then sends no "add a note" notification for the call.

Returns a Bundle with `result`: `added`, `known` (already queued), `later` (row not found: the
backup sync picks it up), `sync_off`, or `error`. The main app reads everything else (number,
direction, time, length, SIM) from that call-log row itself and queues it exactly as the
call-log sync would (same id): the sync, kept running as a backup, never counts it twice and the
"add a note" notification comes once. Only lead calls are handed over by ITTC Phone.

## 4. The "Leads" tab and after-call results (2.10.5+)

    query content://com.abamunim.leads.leadlookup/my_leads

The signed-in user's own open leads (assigned to them, not Admitted / Not Now / Cancelled, ≤ 500),
from `my_phone_leads()`: columns `lead_id, name, phone, alt_phone, whatsapp_number, stage, course,
batch, rating, next_call, last_contacted` (ISO times or null) `, call_attempts, last_note`. Kept in
this app's private storage, refreshed every 5 minutes while it runs and right after a lead changes;
cleared on logout.

    contentResolver.call(…, "save_outcome", <call log _ID or null>, Bundle{ lead_id, number,
                         outcome, note, next_at })

`outcome`: `connected`, `no_answer`, `busy`, `switched_off`, `wrong_number`, `call_back` (optional);
`note` (optional); `next_at` = the next call, epoch ms (optional). Returns `queued`; this app sends
it (`save_phone_call` on the call itself when the call-log row is known, `schedule_follow_up` for
the next call) at once when it is running, else on its next sync.

    contentResolver.call(…, "refresh_leads", null, null)

Asks this app for a fresh list (`asked` when it is running, else `later`).

### Live list, stages and super admins (2.10.6+)

- `my_leads` also has `stage_id, course_id, assigned_to`. A **super admin** gets every open lead
  (≤ 2000, their own first) from `my_phone_data()`; everyone else only their own (≤ 500).
  ITTC Phone 3.11 signed in by itself calls `my_phone_data(p_all => true)`: every open lead the
  person may see in the Tracker (row security decides; ≤ 2000, their own first) and `people` with
  the owners' names, for the Leads tab's "My list" / "All list" switch. An older database (404):
  the call without arguments, as before (only on PostgREST's `PGRST202`). A lead there that the person may
  see but not change carries `"can_edit": false` (the phone then offers no stage move, note or edit). The Tracker's own call (no arguments) is unchanged, so in
  Tracker mode the switch is not offered (a super admin keeps their whose choice).
- Each new list is announced with `notifyChange(content://com.abamunim.leads.leadlookup/my_leads)`:
  ITTC Phone registers a `ContentObserver` on that URI and re-reads it. The app fetches a new list
  ~2 s after the server pings it (Supabase Realtime INSERTs on `phone_lead_pings`, added by triggers
  when a lead's owner, stage, next call… changes; a super admin's "any lead" ping waits up to 20 s)
  while it runs, and when asked (`refresh_leads`).

      query content://com.abamunim.leads.leadlookup/phone_meta

  One row, one column `json`: `{ me: {id, super}, stages: [{id, key, name, color, note_required,
  ask_date, date_label, follow_up_days, course_ids}], people: [{id, name, role}], at }` — the open
  pipeline stages in order (`course_ids` null = every course), the Team Leads and Callers (super
  admins only), and when the list was fetched. None before the first fetch / with an older app.

- `save_outcome` takes `stage_id` (+ `stage_note` "1": the stage wants the note; `stage_due` "1":
  it wants a date, which is `next_at`). This app moves the lead with `move_lead_stage` before the
  next call is scheduled; a stage the server refuses becomes a note on the lead.

## Opening a lead (ITTC Phone → this app)

    Intent(ACTION_VIEW, "leadmanagement://leads/<lead_id>?callNote=<call log _ID>").setPackage(…)

`callNote` (optional) opens the lead with the note popup for that call, as the "add a note"
notification does. ITTC Phone checks this app's certificate before opening it.

## Direct calls (this app → the phone app)

With "Make and manage phone calls" allowed (asked on the first call), this app's Call button
places the call through `TelecomManager.placeCall`: no dial pad in between. Without it, `tel:`
opens the dial pad as before.

## Launch extra

The main app opens ITTC Phone (with the boolean extra `ask_default` = true) when the user taps
"Make it the phone app"; ITTC Phone asks for the phone (dialer) role by itself whenever it opens
and is not the phone app yet.

## 3. Without ITTC Phone

Not installed, or not the default phone app: nothing changes, calls sync from the call log as
before.

## 5. ITTC Phone signed in by itself ("direct mode", ITTC Phone 3.2.0 / Tracker 2.11.0)

A person can sign in inside ITTC Phone (menu → **ITTC account**) with their Tracker e-mail and
password. ITTC Phone then talks to the institute's Supabase server itself (code in
`app/src/main/kotlin/org/fossify/phone/direct/`), so the Tracker app is not needed.

- **Which way it works**: direct when signed in *and* (the Tracker is not signed in on this phone,
  or the person turned on **Run without the Tracker**). Otherwise everything goes through the
  Tracker as in 1–4. `IttcBridge` routes each request (`IttcDirect.isActive`).
- **No call saved twice**: ITTC Phone tells the Tracker with
  `call(…, "direct_mode", "1" | "0", null)` → `{ result: "ok" }` (Tracker 2.11.0+, certificate
  checked as for every call). While it is on, the Tracker's call-log sync drops its pending calls
  and sends none, and it stops fetching the Leads list for ITTC Phone.
- **Only lead calls**: the server's `my_lead_directory()` gives SHA-256 codes of the person's lead
  numbers (as 01XXXXXXXXX); a call is queued only when its number's code is on that list.
- **Server functions** (migration `20270104063500_ittc_phone_direct.sql`): `ittc_phone_session`
  (allowed? signed out by an admin? who I am, the admin's settings; the phone's id, model and app
  version are recorded), `ittc_phone_forget`, `my_phone_data`, `my_lead_directory`,
  `phone_lead_detail`, `phone_today`, and the Tracker's own `sync_phone_calls`, `save_phone_call`,
  `add_lead_note`, `move_lead_stage`, `schedule_follow_up`, `hand_over_leads`, `log_template_sent`,
  `create_lead`. Live updates: Supabase Realtime on `phone_lead_pings`.
- **Admin** (Tracker → Settings → ITTC Phone): allow / stop signing in per person (default
  allowed), see each person's phones and sign one out, note presets, next call by result, daily
  call target, lowest app version.
- **On the phone**: the session is encrypted with an Android Keystore key; the lists are private
  files; changes wait in an outbox (sent when online, kept up to 7 days). Signing out (or being
  signed out by an admin) removes all of it.
- **Build**: the server URL and the public (anon) key come from the repository secrets
  `SUPABASE_URL` and `SUPABASE_ANON_KEY` at build time; without them the sign-in is hidden.
