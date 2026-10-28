# Home layout stabilization and diagnostics

User controls are documented in README. The repair profile is independent of Android 16: SDK 37 / BZI9 / Launcher 1800705053 / Home Up 1800005036 only. Existing Android 16 hooks are unchanged.

## Evidence and scope

Native logs showed portrait bounds with landscape side cutouts, transient 1330→1203→1330 workspace widths, and 18 persisted layout sanitation passes. Float32 replay reproduced 65 of 66 item positions exactly, including all 21 small coordinate changes. One separate per-item movement remains unattributed; the patch does not guess a restore position. Widget spans and saved scale were unchanged while measured sizes shrank; restarting Launcher restored dimensions.

Private APKs, decompiled sources, original databases/WALs and logs are not distributed or committed.

## Repair contract

Home Up is loaded dynamically. The workspace observer resolves the actual Home Up loader, validates the build and all core signatures, and registers the repair once. Stale side cutouts in portrait (or vertical cutouts in landscape) are replaced only by current WindowMetrics whose bounds and cutout agree with Configuration. If no consistent input exists, the last native bounds are retained.

Native UPDATE_LAYOUT item sanitation is coalesced for at least 750 ms. It must agree with current WindowMetrics, native bounds and attached measured page dimensions; active touches postpone it. After ten seconds without stable geometry the pending operation is abandoned without writing. A later native layout event can retry. As of 0.19.1, successful updates call only the original native sanitation once while Launcher is resumed, Recents is not resumed, and an attached page has window focus. Native widget factories, styles and layout parameters are never rebuilt by the guard. The rendering observer does not synthesize sanitation calls. IDs, saved scale, spans and coordinates are not guessed or directly edited in the DB.

The sanitation entry also rejects inconsistent current metrics. DEX call sites are deoptimized off the UI thread to prevent compiled native callers bypassing interception. A resolution/callback failure disables the new repair; existing features remain separate. Framework crash protection still covers the launcher host.

## Journal

The authenticated launcher sends bounded batches to the module ContentProvider on a background HandlerThread. JSONL includes wall time, uptime, session, PID and sequence; configuration/geometry events, sanitation reasons/results, repository writes with previous/current item geometry and native call stack, activity lifecycle, home gesture category and launch components. Text input, intent extras, notification content, screenshots and DB contents are excluded. Coordinates/app components can still be sensitive; export is explicit.

The journal uses four rotating ~1 MiB app-private files. In-memory backlog is bounded to 256 records, with dropped counts. Up to two seconds before an abrupt process death may remain unflushed. Bulk/background writes can be correlated by call path, but the journal does not promise identification of every external operation. Export uses a non-exported FileProvider with a cache-subdirectory grant; append/config access checks the calling UID/package. Logs never go into release/source archives.

## Validation

JVM cases cover the observed portrait/landscape cutout mismatch, all four settled orientations, mismatched window sizes and zero bounds. The package workflow also runs the existing JVM suite and release Lint. Device validation is command-only: installation, launcher-only restart, load/journal state and measured geometry. No injected input or forced rotation is authorized for this session; repeated real rotation/drag behavior remains a later manual regression check.

### Command-only device result (2026-10-28)

Signed release 0.19.0 was installed over the existing package. Launcher was restarted without changing SystemUI/system_server processes. The final session emitted `ready`, `repository_observed`, `layout_deferred`, and `callsites_ready` (28 callsites across host/plugin). Native repository writes were captured with item geometry and the `onLoadPartialComplete` caller path. No `repair_disabled`/`unsupported` event occurred in the final session.

While the phone remained Dozing, measured FreeGrid pages were 0×0. The pending update therefore emitted `layout_abandoned` without running the deferred sanitation. No wake/unlock, input injection, forced rotation, DB restoration or preference changes were performed. Live widget reapplication, repeated rotation and the diagnostics screen/export have not been visually exercised in this restricted session. JVM suite: 115 tests, zero failures/errors/skips; release Lint and signed package validation passed.

Raw DEX signatures take precedence over decompiler aliases: the VM accessor is `isPreview`, and `getViewModel` / `getBaseCellSize` are private. The host WindowBounds implementation belongs to the launcher loader; FreeGrid contracts belong to the dynamic Home Up loader.

## 0.19.0 regression and 0.19.1 correction

The diagnostic journal identified the regression at 2026-10-28 19:04:02.776: while portrait Recents was resumed and Launcher paused, the synthetic widget refresh assigned a 2730px layout width to a 7-column widget on a 1330px page. All 29 recorded repository writes in that update retained the exact before/after coordinates; the large visual change was from the added style/factory/parameter mutation, not a new coordinate scaling ratio (which was 1,1). The old widget refresh and all its reflective contracts were removed rather than attempting another factory reconstruction. Original diagnostic records are retained privately.

Lifecycle observation now starts during Application attachment, before either activity is created. A weak set tracks resumed activities so recreation cannot leave incorrect booleans. Background/overview updates wait for Home to resume instead of being replayed in Recents. Native WindowBounds interception is scoped to the Launcher-named bounds instance, excluding Recents and previews. The sanitizer also refuses changes while Recents is resumed. Tests replay the observed foreground/overview overlap and check that even stable portrait measurements cannot authorize an overview write. Existing diagnostics and Android 16 behavior are retained.

The corrective release is packaged before command-only installation and a Launcher-only reload. No automatic DB restoration is used: user changes since installation must remain intact.
