# 📞 ITTC Phone ↔ ITTC Admission Tracker

ITTC Phone is a separate app (made from Fossify Phone, GPL-3.0, source at
github.com/aamunim/ittc-phone). Its app id stays `org.fossify.phone` (Fossify's shared
library needs it). This app stays private; the two only talk through the provider below.

## Who may talk to whom: certificate lists

No shared permission. This app comes from Google Play, re-signed with Google's key (Play App
Signing), which ITTC Phone can never carry; two apps declaring one permission with different
keys cannot even be installed together. Instead each side checks the other's signing
certificate (SHA-256) against a fixed list, on every request:

| app | certificates it is accepted with |
|---|---|
| ITTC Phone (`org.fossify.phone`) | upload key `2955a704…0d83` (GitHub Actions signs with it) |
| Tracker (`com.abamunim.leads`) | Google Play app signing key `d07ea3e9…4e54`, upload key `2955a704…0d83` (APK builds) |

The lists live in `IttcPhone.kt` (here) and `IttcBridge.kt` (ITTC Phone). A new signing key
means adding its SHA-256 on both sides.

## Android 11+ package visibility

Each app lists the other in `<queries>` (this one: `org.fossify.phone`; ITTC Phone:
`com.abamunim.leads`).

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
