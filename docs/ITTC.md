# 📞 ITTC Phone ↔ ITTC Admission Tracker

ITTC Phone is a separate app (made from Fossify Phone, GPL-3.0, source at
github.com/aamunim/ittc-phone). This app stays private; the two only talk through
the Android intents below. Both apps must be **signed with the same key**.

## Permission (declared by both apps, `protectionLevel="signature"`)

    com.abamunim.leads.permission.ITTC_PHONE

## 0. Things ITTC Phone must have

- **Signed with exactly the key the installed main app carries.** The main app is built by
  EAS (`credentialsSource: remote`): export that keystore with `eas credentials` and sign
  ITTC Phone with it. If the installed main app ever carries a different key (e.g. Play App
  Signing), the bridge cannot work, and the second app to be installed fails with
  INSTALL_FAILED_DUPLICATE_PERMISSION — both apps declare the permission.
- Android 11+ package visibility: `<queries><package android:name="com.abamunim.leads" /></queries>`
  (visibility is not mutual: without it the lookup and the broadcast silently fail).
- `<uses-permission android:name="com.abamunim.leads.permission.ITTC_PHONE" />` and the same
  `<permission … android:protectionLevel="signature" />` declaration.

## 1. Is this number a lead? (ITTC Phone → this app)

    content://com.abamunim.leads.leadlookup/number/<number>

One row or none, columns:
`lead_id, name, rating, stage, course, last_note, student_ref, fee_due, next_call, owner`.
One number at a time; the list itself is never handed out.

## 2. A call ended (ITTC Phone → this app)

Broadcast with `setPackage("com.abamunim.leads")` (plus `FLAG_INCLUDE_STOPPED_PACKAGES`),
action `com.abamunim.leads.ITTC_PHONE_CALL_ENDED`, sent with
`sendBroadcast(intent, "com.abamunim.leads.permission.ITTC_PHONE")`.

| extra | type | |
|---|---|---|
| `call_log_id` | String | **required**: the system call log's `_ID` for this call — read it *after* the system wrote the row (poll the call log for a few seconds after DISCONNECTED) |
| `number` | String | the call's number (only used to check the row is that call) |
| `end_reason` | String | optional: `busy`, `no_answer`, `declined`, `error`, … (letters, digits, `_`) |

The main app reads everything else (number, direction, time, length, SIM) from that
call-log row itself, so the call is queued exactly as the call-log sync would queue it
(same id): the sync, kept running as a backup, never counts it twice and the "add a note"
notification comes once. Wrong / missing id: ignored, the backup sync picks the call up.
Only lead numbers are kept; all others are dropped by the server.

## Provider use

Query off the main thread (it may start the main app's process), with the number
`Uri.encode`d (`+880…`).

## Launch extra

The main app opens ITTC Phone with the boolean extra `ask_default` = true when the user
taps "Make it the phone app": ITTC Phone should then ask for the phone (dialer) role.

## 3. Without ITTC Phone

Not installed, or not the default phone app: nothing changes, calls sync from the
call log as before.
