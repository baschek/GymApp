# Native Galaxy Watch application

The `:watch` application uses native Wear Compose Material 3 with Hanken Grotesk,
a round black layout, touch alternatives to rotary input, workout preparation,
set entry, rest, history, health and connection views. It shares the phone package
ID and must be signed with the same certificate for Wear Data Layer communication.
Install the phone and Watch APKs on their respective devices. No release is
published by this change.

Debug APKs communicate with the correspondingly debug-signed phone app. A Watch
APK for an existing release-signed phone installation must use that same release
certificate. The existing release workflow builds both signed APKs when the owner
explicitly runs a release; creating this PR does not publish one. Do not uninstall
the phone application or erase its training data to work around a signing mismatch.

## Architecture

- `:core` contains existing progression/training algorithms and the Wear event
  protocol. Events carry stable session, set and operation IDs; independent edits
  merge by exercise/set identity and conflicting changes remain explicit. Conflict
  choices are propagated as idempotent resolution events.
- `:wear-backend` stores the Watch catalog, workout state, replay receipts and
  outgoing changes locally. It reuses core progression, per-gym weights and set
  editing. Pending events survive starting another workout.
- `:watch` hosts the native UI, recording service, alarms and companion transport.
- The phone publishes catalog data and imports Watch events through its repository.
  The Watch never receives server credentials or pairing tokens. Initial catalog
  availability requires a paired phone; a cached workout can then run offline.

Data Layer is asynchronous: Bluetooth is preferred when available, and Google
Play services can route traffic through Google infrastructure when necessary.
An acknowledgement follows a committed import or a recorded terminal rejection.
Events continue to use individual DataItems and Assets. Receivers allow a short
grace period for a partial batch before attempting its items in predecessor order.
Both devices remain operable while disconnected.

The last known operator coordinates timer/alarm leadership after event delivery.
During a network partition, both independently operated devices cannot know the
other's latest action; globally exactly-once alarms cannot be guaranteed in that
case. A connection indicator must not imply a completed synchronization.

## Health data

Health Services captures heart rate on the Watch only after an explicit user
action and runtime permission grant. The host supplies a health foreground
service. A bounded append-only binary sample journal advances an atomic metadata
checkpoint after fsync; uncommitted tails are ignored after process death.
Completed recordings must enter a durable local queue before clearing the active
recorder. Sensor accuracy and missing periods are retained. Values are never
fabricated or interpolated across gaps.

Health Connect export runs on the phone with explicit consent. Stable record
identifiers, monotonic versions, bounded chunks and a persisted attempt fence
protect retries and corrections. `READ_HEART_RATE` is needed only to reconcile
this app's own exports, filtered by DataOrigin and exact session chunk IDs; export
also requires `WRITE_EXERCISE` and `WRITE_HEART_RATE`. TEST data stays local and
is never sent to the peer or Health Connect.

On Android 13 and older, old historical queries may be restricted; export returns
`UnsupportedHistory` instead of claiming a completed reconciliation. Android 14+
allows unrestricted history reads of the application's own records. Export may
partially succeed before interruption; retry the latest stored version.

Health Connect and Samsung Health are separate products. Samsung Health visibility
depends on its permissions, synchronization settings and supported records and
requires testing on the actual paired devices. No direct Samsung SDK partnership
or automatic Samsung Health import is claimed.

## Platform decisions and sources

Checked against official documentation and Maven metadata on 2026-11-16:

- [Wear Compose](https://developer.android.com/training/wearables/compose): use Wear
  Material 3, not mobile Material components; stable Wear Compose 1.7.1.
- [Rotary input](https://developer.android.com/training/wearables/compose/rotary-input):
  focus-aware rotary events with touch alternatives. Device haptic behavior must
  be verified on the Galaxy Watch hardware.
- [Ambient mode](https://developer.android.com/training/wearables/always-on): ambient
  updates are generally minute-based; avoid continuous animations or pretending a
  seconds-accurate ambient display is guaranteed.
- [Data Layer](https://developer.android.com/training/wearables/data/overview):
  same package/signature, Play services availability and asynchronous delivery.
- [Health Services](https://developer.android.com/health-and-fitness/health-services):
  exercise ownership, available metrics, runtime permissions and a foreground host.
- [Own Health Connect records](https://developer.android.com/health-and-fitness/health-connect/read-data#read-previously-written-data):
  history restrictions differ before Android 14.
- [Samsung Galaxy Watch development](https://developer.samsung.com/galaxy-watch):
  Galaxy Watch apps use the Wear OS application platform.

AndroidX Health Services marks its documented ownership/end-reason constants as
restricted even in version 1.1. Two narrowly scoped compatibility helpers retain
these officially documented checks; unknown ownership fails closed. Lint is not
disabled globally.

## Validation and physical acceptance

Run core tests, both applications' unit tests/lint/debug builds and the backend
library tests/lint/assemble. Android CI should publish both APKs and library
reports. No Room entity/schema migration is needed for device-local settings.

Before using on real training data, verify a full disconnected workout, process
recreation, repeated delivery, concurrent edits/resolution, permission revocation,
Watch-to-phone switching, timed holds, round-screen clipping/48dp touch targets,
rotary input, ambient readability, alarm/DND behavior, battery life and actual
Samsung Health visibility. Unit tests cannot establish those hardware properties.

The separately recorded To-do/packing-list button remains outside this change.
