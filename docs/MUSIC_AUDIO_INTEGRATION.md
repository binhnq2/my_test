# Music and audio integration

Local and USB music, played by a foreground service, with audio focus, a
media session, and ducking for navigation guidance. This document covers what
plays, who owns playback, and exactly what happens at every audio seam — a
phone call, a navigation prompt, a pulled USB stick, an ignition-off.

## 1. Who owns what

```
MusicPlaybackService  (foreground, process-level, START_STICKY)
 ├── LocalMusicBackend
 │    ├── MusicDatabase         SQLite index of the library
 │    ├── SqliteMusicLibrary    query side, markReady/markDirty
 │    ├── MediaStoreScanner     incremental scan, Android + USB walk
 │    └── LocalMusicPlayer      MediaPlayer owner, holds the PlaybackCore
 │         └── PlaybackCore     every play/pause/queue decision, no Android
 ├── MediaSessionCompat          portable metadata + AVRCP (vendor Bluetooth)
 ├── framework MediaSession      system media notification + lock screen
 └── AudioFocusListener          installed on AppGraph.audioBridge
```

**Why a Service, not an Activity.** `MediaPlayer` owned by an Activity dies with
it. The spec requires music to survive opening the map, opening the music
screen, and an Activity recreate. The player, its queue and its resume position
therefore live in the service, and every screen binds to it.

**Why foreground.** On API 23 a background service is killed when the system
wants memory, and this head unit wants memory constantly while rendering the
map. A foreground service with an ongoing notification is the only arrangement
that keeps playing while the driver sits on the navigation screen.

**Why `PlaybackCore` is separate from `LocalMusicPlayer`.** The behaviours that
actually break in a car — resuming music the driver never asked to resume,
leaving the stream ducked forever, losing the resume position — are
*decisions*, not audio plumbing. `PlaybackCore` touches no Android API, so those
decisions are verified on the JVM by 27 tests. A decision that only runs on the
vehicle is a decision nobody verified.

## 2. Scanning

Two paths, one bookkeeping.

| source | path | why |
|--------|------|-----|
| internal storage | `MediaStore` (`ACTION`-free query) | the platform already walked it; reading its index is nearly free |
| USB / SD | direct directory walk + `AudioTagReader` | this firmware's `MediaStore` ignores removable volumes unless the vendor's media app has scanned them |

Both converge on the same `ScannedFile` and the same `ScanDiffCalculator`, so
incremental behaviour is identical regardless of where the file came from.

**Incremental, and why it matters here.** A full scan of a 2,000-track stick on
this CPU costs tens of seconds and competes with map rendering for the same
cores. So:

- the **first** scan (or a forced one) indexes everything and records each
  file's size and mtime
- every later scan compares size+mtime and touches only what changed —
  unchanged files cost a `stat()`, not a tag read

**Stable ids for files the platform database does not number.** MediaStore hands
out small sequential row ids. A USB file identified by a path hash is pushed
into a different range with **bit 62 set** (`TrackIds.forUsbPath`). Without
that, an FNV-1a hash that happened to land on a MediaStore id would silently
overwrite an unrelated track — a bug that would appear on exactly one car and
be unreproducible anywhere else. FNV-1a over the path is chosen because it
depends only on the path, so it is stable across reboots, which is what makes
the incremental rescan possible at all.

**USB scanning writes nothing outside the app's database.** `MediaStore` is not
touched. If the stick is yanked mid-walk the walk throws `IOException` and the
partial result is kept; the next scan picks up the rest.

## 3. Playback

`MediaPlayer`, not ExoPlayer. Two reasons: the APK must stay small enough to
sideload over ADB onto a head unit with a slow link, and the formats the spec
lists are exactly the ones the platform decoder handles. The trade-off is that
`MediaPlayer` is stateful and throws differently by firmware, so every call is
guarded and every failure becomes a `PlaybackError`.

| behaviour | implementation |
|-----------|----------------|
| universal "previous" convention | `RESTART_THRESHOLD_MS = 3 s` — a press inside 3 s restarts the track, later goes back |
| end of a non-repeating queue | last track stays loaded and paused, so the screen still shows what was playing |
| play with nothing loaded | resumes the checkpoint — what a driver pressing play on a cold boot expects |
| wake lock | `PARTIAL_WAKE_LOCK` via both `setWakeMode` and an explicit lock, 6 h timeout |
| `MediaPlayer.currentPosition()` after release | never called; position is snapshotted, because it throws after `release()` |

### 3.1 Checkpointing

Position is saved on pause, on stop, on focus loss, and every
`CHECKPOINT_INTERVAL_MS = 10 s` while playing. An ignition-off loses at most ten
seconds of position. That is why `MusicPlaybackService` can be `START_STICKY`:
after a low-memory kill, the restore is seamless.

## 4. Audio focus

`AndroidVehicleAudioBridge` is the single seam between the launcher and the
audio hardware. It implements the platform's `AudioManager` focus API — API 26's
`AudioFocusRequest` and the API 23 deprecated overload both, so the same code
runs on the unit and on a modern emulator for testing.

What it actually does on this firmware, and **what it does not**:

| action | works | mechanism |
|--------|-------|-----------|
| request / abandon media focus | yes | public `AudioManager` API |
| detect a call and stop | yes | `AUDIOFOCUS_LOSS_TRANSIENT` |
| duck for navigation | yes | `setStreamVolume(STREAM_MUSIC, ×0.25)`, restored on unduck |
| pause FM/AM radio | **no** | vendor app owns the tuner, no SDK |
| pause Bluetooth playback | partial | needs AVRCP, see below |

The radio row is the load-bearing one. The spec forbids reporting success for a
privileged action, so `pauseExternalSource()` returns
`AudioResult.Unsupported` rather than pretending. The UI renders that as a
disabled control with an explanation — "chuyển nguồn bằng tay" — not a silent
failure.

### 4.1 The call sequence

1. A call arrives → `AUDIOFOCUS_LOSS_TRANSIENT` → `onCallStarted()`.
2. `PlaybackCore.onCallStarted()` records `pausedForCall = true` **only if it was
   playing**, and returns `PAUSE`.
3. The call ends → `onCallEnded()`. `pausedForCall` decides: a driver who had
   paused their own music does not get it resumed by a phone call.

That one flag is the entire reason `PlaybackCore` exists. In the original
implementation it was a boolean tangled with `MediaPlayer` calls, and its bug —
music resuming after a call the driver never had playing — could only be
observed on the vehicle.

### 4.2 The duck sequence

```
VoiceGuidance.speak()  →  audioBridge.duck()   (volume × 0.25, remembered)
        … utterance …
UtteranceProgressListener.onDone/onError/onStop  →  audioBridge.unduck()
```

Paired through the listener, **not a timer** — a long street name would outlive
any timer and leave the music quiet with no explanation.

Three defences against a permanently ducked stream:

1. `duck()` is **idempotent** — a second prompt while already ducked returns
   `Success` without re-reading the volume, so `volumeBeforeDuck` still holds
   the real original level.
2. A duck that would land `target >= current` (the volume is already at the
   floor) is *not* recorded as ducked, because there is nothing to undo.
3. `speak()` failing to queue calls `endUtterance()` immediately, because `onDone`
   will never fire and the music would otherwise stay ducked.

`unduck()` restores `volumeBeforeDuck`, then clears it to −1. There is
deliberately no "mirror" counter: `speaking` is a single boolean because
`QUEUE_ADD` utterances are serialised by the engine, and the listener fires once
per utterance.

### 4.3 When ducking is refused

If `duck()` returns `Denied`, `Unsupported` or `Failed`, `VoiceGuidance` **speaks
anyway**. The instruction is safety-relevant; the music being too loud is the
lesser failure. That is a deliberate ordering, not an oversight.

## 5. Media session

Both session APIs are fed, from one `PlaybackState` object:

| API | consumed by |
|-----|-------------|
| `MediaSessionCompat` (androidx) | vendor Bluetooth stack over AVRCP; portable metadata |
| framework `android.media.session.MediaSession` (API 21+) | system media notification, lock-screen transport |

Feeding only the compat session leaves the notification's play/pause dead on
some firmware; feeding only the framework session loses AVRCP metadata. The
translators live in `SessionBuilders.kt` so both are updated at one call site.

Specifics worth knowing:

- `ACTIONS` is declared **explicitly** rather than derived. androidx.media 1.7
  omits `ACTION_SEEK_TO` from the derived mask on API 23, and the notification's
  seek bar then does nothing.
- `playbackSpeed` is `1f` or `0f`, never anything else. The session API treats it
  as a multiplier, and any other value makes the head unit's progress bar
  wobble.
- No artwork is published. `Track` has no embedded-art path in this build, and a
  placeholder image shown in the car's media widget would be a fake asset.

## 6. Hardware keys

`MediaButtonReceiver` handles `ACTION_MEDIA_BUTTON` because on this firmware the
CANBUS app injects broadcasts rather than going through the session callback.
Without it the steering-wheel keys do nothing while the notification buttons
work — a very confusing failure to diagnose from the driver's seat.

| key | action |
|-----|--------|
| `MEDIA_PLAY` / `MEDIA_PAUSE` / `MEDIA_PLAY_PAUSE` | play / pause / toggle |
| `MEDIA_NEXT`, `MEDIA_FAST_FORWARD` | next |
| `MEDIA_PREVIOUS`, `MEDIA_REWIND` | previous |
| `MEDIA_STOP` | stop |
| `HEADSETHOOK` | toggle — head units commonly use the single multifunction key |

Only `ACTION_DOWN` is handled, so one press is one action.

## 7. USB removal — the crash the spec forbids

Three layers, in the order they fire:

1. **`UsbMediaReceiver`** (manifest, so it runs with the app in the background)
   catches `MEDIA_UNMOUNTED`, `MEDIA_EJECT`, `MEDIA_REMOVED` and
   `MEDIA_BAD_REMOVAL`. All four funnel into one idempotent call, because vendor
   firmware sends different actions for different sticks and yanks the third
   without a prior unmount.
2. **`MusicPlaybackService.onStorageRemoved`** pauses **before** the path becomes
   unreadable — the file is still open and the position is still known — saves
   the checkpoint, and publishes `PlaybackError.SOURCE_UNAVAILABLE`.
3. **`LocalMusicPlayer.open`** checks `sourceExists(track)` *before*
   `setDataSource`. A vanished file throws `IOException` on some firmware and
   `RuntimeException` on others, so even the race is covered.

Every `MediaPlayer` media operation is wrapped, and the error mapper turns
`MEDIA_ERROR_IO`, `FileNotFoundException`, `SecurityException` and a "no such
file" message into `SOURCE_UNAVAILABLE` rather than `UNKNOWN`.

**The `StorageVolume` trap.** The volume extra is read as `Parcelable` and
reflected on, never typed as `StorageVolume`. `StorageVolume` is API 24, so a
typed read is a `NoSuchMethodError` on API 23 — on the very path that exists to
prevent a crash. When the path cannot be recovered the receiver passes null,
which tells the service "a volume changed, re-check everything": the safe
answer, since the service re-verifies each track's file instead of trusting a
path it may not have read correctly.

## 8. Errors, all of them

`PlaybackError` — every one renders a sentence, none renders "error".

| value | meaning | what the driver sees |
|-------|---------|----------------------|
| `SOURCE_UNAVAILABLE` | file gone, usually USB pulled | "Nguồn nhạc đã ngắt" and playback paused |
| `UNSUPPORTED_FORMAT` | codec cannot decode here | the track is skipped and marked |
| `DECODE_FAILED` | file exists but corrupt/truncated | skipped, marked |
| `AUDIO_FOCUS_LOST` | another app took the output and kept it | "Ứng dụng khác đang dùng âm thanh" |
| `UNKNOWN` | anything unclassifiable | generic message with the engine's text |

## 9. Test coverage

`PlaybackCoreTest` (27 tests) covers the decisions without a device: resume
after a call only if it was playing, duck/unduck balance, non-repeating queue
end, previous-track restart threshold. `PlaybackQueueTest` (18) covers shuffle
and repeat semantics. `AudioTagReaderTest` (20) covers the container headers on
real byte fixtures. `CheckpointCodec` / `CheckpointRestore` (17) cover the
save/restore round-trip.

What is **not** covered here, and cannot be without the car: whether this
firmware's AVRCP actually reads the compat session, and whether the tuner can be
paused. Both are recorded as unverified in `KNOWN_LIMITATIONS.md` rather than
claimed as working.

## 10. Where navigation plugs in

`AppGraph.audioBridge` is shared: `VoiceGuidance` requests
`AudioRoute.NAVIGATION` focus and calls `duck()`/`unduck()`; the music player
requests `AudioRoute.MEDIA` focus. There is exactly one focus listener in the
process — the player's — installed by `MusicPlaybackService.onCreate`:

```kotlin
graph.installFocusListener(created.player.asFocusListener())
```

Nothing else in the process reacts to focus changes. That single-owner rule is
what prevents two components each pausing the other's audio.
