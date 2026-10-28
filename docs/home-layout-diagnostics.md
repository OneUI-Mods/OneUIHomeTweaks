# Home layout stabilization and diagnostics

User controls are documented in README. The repair profile is independent of Android 16: SDK 37 / BZI9 / Launcher 1800705053 / Home Up 1800005036 only. Existing Android 16 hooks are unchanged.

## Evidence and scope

Native logs showed portrait bounds with landscape side cutouts, transient 1330→1203→1330 workspace widths, and 18 persisted layout sanitation passes. Float32 replay reproduced 65 of 66 item positions exactly, including all 21 small coordinate changes. One separate per-item movement remains unattributed; the patch does not guess a restore position. Widget spans and saved scale were unchanged while measured sizes shrank; restarting Launcher restored dimensions.

Private APKs, decompiled sources, original databases/WALs and logs are not distributed or committed.

## Repair contract

Home Up is loaded dynamically. The workspace observer resolves the actual Home Up loader, validates the build and all core signatures, and registers the repair once. Stale side cutouts in portrait (or vertical cutouts in landscape) are replaced only by current WindowMetrics whose bounds and cutout agree with Configuration. If no consistent input exists, the last native bounds are retained.

Native UPDATE_LAYOUT item sanitation is coalesced for at least 750 ms. It must agree with current WindowMetrics, native bounds and attached measured page dimensions; active touches postpone it. After ten seconds without stable geometry the pending operation is abandoned without writing. A later native layout event can retry. Successful updates call the original native sanitation once, then recreate widget layout parameters through native methods with current style dimensions. IDs, saved scale, spans and coordinates are not guessed or directly edited in the DB.

The sanitation entry also rejects inconsistent current metrics. DEX call sites are deoptimized off the UI thread to prevent compiled native callers bypassing interception. A resolution/callback failure disables the new repair; existing features remain separate. Framework crash protection still covers the launcher host.

## Journal

The authenticated launcher sends bounded batches to the module ContentProvider on a background HandlerThread. JSONL includes wall time, uptime, session, PID and sequence; configuration/geometry events, sanitation reasons/results, repository writes with previous/current item geometry and native call stack, activity lifecycle, home gesture category and launch components. Text input, intent extras, notification content, screenshots and DB contents are excluded. Coordinates/app components can still be sensitive; export is explicit.

The journal uses four rotating ~1 MiB app-private files. In-memory backlog is bounded to 256 records, with dropped counts. Up to two seconds before an abrupt process death may remain unflushed. Bulk/background writes can be correlated by call path, but the journal does not promise identification of every external operation. Export uses a non-exported FileProvider with a cache-subdirectory grant; append/config access checks the calling UID/package. Logs never go into release/source archives.

## Validation

JVM cases cover the observed portrait/landscape cutout mismatch, all four settled orientations, mismatched window sizes and zero bounds. The package workflow also runs the existing JVM suite and release Lint. Device validation is command-only: installation, launcher-only restart, load/journal state and measured geometry. No injected input or forced rotation is authorized for this session; repeated real rotation/drag behavior remains a later manual regression check.
