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
`lead_id, name, rating, stage, course, last_note, student_ref, fee_due, next_call, owner`.
One number at a time; the list itself is never handed out. Query off the main thread.

## 2. A call ended (ITTC Phone → this app)

    contentResolver.call(Uri.parse("content://com.abamunim.leads.leadlookup"),
                         "call_ended", <call log _ID>, Bundle{ number, end_reason })

- `arg`: the system call log's `_ID` for the call, read *after* the system wrote the row.
- `number`: only used to check that the row is that call.
- `end_reason` (optional): `hung_up`, `they_hung_up`, `busy`, `missed`, `declined`, `cancelled`,
  `error`, `restricted`, `answered_elsewhere`, `pulled` (letters, digits, `_`).

Returns a Bundle with `result`: `added`, `known` (already queued), `later` (row not found: the
backup sync picks it up), `sync_off`, or `error`. The main app reads everything else (number,
direction, time, length, SIM) from that call-log row itself and queues it exactly as the
call-log sync would (same id): the sync, kept running as a backup, never counts it twice and the
"add a note" notification comes once. Only lead calls are handed over by ITTC Phone.

## Launch extra

The main app opens ITTC Phone (with the boolean extra `ask_default` = true) when the user taps
"Make it the phone app"; ITTC Phone asks for the phone (dialer) role by itself whenever it opens
and is not the phone app yet.

## 3. Without ITTC Phone

Not installed, or not the default phone app: nothing changes, calls sync from the call log as
before.
