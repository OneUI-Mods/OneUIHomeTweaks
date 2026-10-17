# Portrait Recents: motion adapter investigation

Status: **0.10.0 release candidate**. The common-viewport controller and independently optional live rendering are now part of normal builds; the latter defaults OFF. They have been exercised with the documented Launcher/Home Up pair, including Task Changer ON and OFF and a real hardware-video Surface. Perspective cards temporarily use native snapshots while the app keeps drawing. This is a device-specific implementation, not a guarantee for every app, layout or interruption. The dated sections below preserve earlier failures and superseded prototypes.

## Preserved device baseline

Measured on 2026-10-18 JST: SM-S948Q, Android 16 / SDK 36, One UI 8.5, build BP4A.251205.006.S948QOPS1AZHL; One UI Home 17.5.03.27; Home Up 17.5.00.30; Vector 2.2 / API 102. Natural display size 1440 x 3120. These findings are specific to the installed APKs; retain contract checks after updates. The private APK/decompilation baseline is not distributed.

## Verified components and data flow

| Component | Verified role |
|---|---|
| `com.android.quickstep.RecentsActivity` | Final Activity; native LOCKED/USER orientation requests are overridden only for this Activity on the main fullscreen display |
| `com.honeyspace.gesture.recentsanimation.RecentsAnimationManager` | `createFloatingPlayer(RemoteAnimationTargets, TransitionInfo)` creates the app player; `move(LeashTask)` and `moveTaskByPlayer` supply native target bounds and progress |
| `com.honeyspace.gesture.overlaywindow.LeashOverlayWindowImpl` | Separate transient gesture window, title `RecentsTransitionOverlay`, type 2632; dormant size 1 x 1, expanded fullscreen for a gesture |
| `com.honeyspace.gesture.presentation.GestureTaskListView` | Gesture cards; `move(MotionEvent, Rect)` rejects mismatched aspect orientation; `getTaskSceneViewRect` and `sendLeashTaskPosition` supply the app target rectangle |
| `com.honeyspace.ui.common.recentstyler.RecentStylerRepositoryImpl` | Activity-backed Recents styler is separate from per-display WindowContext-backed gesture stylers |
| `com.honeyspace.transition.anim.floating.PlayerImpl` | `moving(RectF,float,float,float)` calculates the live app pose; `makeUpdateInfo(SurfaceTransaction)` builds the actual app Surface update |
| `com.honeyspace.transition.anim.floating.utils.UpdateInfo` | `asMatrix()` produces uniform scale and translation; it does not contain a rotation component |
| `com.honeyspace.transition.anim.floating.utils.RemoteTargetUpdaterKt` | `setUpdate(RemoteAnimationTarget,UpdateInfo)` writes that matrix, native crop, alpha and corner radius to the selected leash |
| `com.honeyspace.transition.utils.TransitionUtils` | `getRotationChange` and `transformMatrix` define native Home-to-window coordinate conversion |
| `com.honeyspace.ui.honeypots.tasklist.presentation.TaskView` | Actual final card; `getFirstTaskId`, `getTaskSceneView` identify its content |
| `com.honeyspace.ui.common.taskScene.SceneStateInfo` | Native thumbnail crop and position matrices, separate from the outer card bounds |

`showRecentsWhenEndRecentsEntering()` switches from the overlay leash to the final Recents target. `notifyFinish()` is a later invalidation notification and must not be used as a visual handoff barrier. `finish()` marks gesture end, not completion of every remote animation.

## Measurements

With the final Recents Activity forced portrait, the gesture root still reports 3120 x 1440 / rotation 1. The final Activity uses 1440 x 3120. A recorded player Home-to-window matrix was `[0 1 0; -1 0 1440; 0 0 1]`, while the actual native app Surface matrix remained uniform scale plus translation. This demonstrates distinct coordinate bases; it does **not** prove the parent Surface transform or the correct replacement matrix.

A private Instrumentation helper injects DOWN/MOVE/UP with one `downTime` and a stable physical path. In the measured run, input displacement was x=0 and y approximately -606 pixels. Separate shell `input motionevent` commands do not establish equivalent gesture timing. Reaching portrait at the end is insufficient evidence of animation continuity.

The later card target moved offscreen during the recorded native animation. Whether this is native task selection, overlay layout or handoff behavior still requires matching the remote app task ID to the actual final thumbnail. Do not infer a fix from those outer bounds alone.

## Matching the closing app to its actual thumbnail

A subsequent measurement matched the remote closing app and final card by the same task ID. The app Surface crop/source was 3120 x 1440; its corresponding snapshot was also 3120 x 1440, rotation 1, scale 1. The portrait card used a real shader matrix approximately `[0 -0.6955 970.9; 0.6946 0 -97.2; 0 0 1]`, followed by its measured View-to-global transform. These are anonymized geometry values, not screenshots.

In that run, converting the full app into the portrait basis and the snapshot shader into the same basis gave the same approximately +90-degree content angle at both ends. A new interpolated rotation is therefore **not necessarily needed** for this case. Position, scale, crop, the gesture viewport and the final handoff still need one consistent mapping. These measurements supersede any initial assumption that rotating the content by another quarter turn would fix the problem.

The native shader also has small nonuniform scaling caused by layout/rounding, and other cards use shear for the selected task-changer style. Exact endpoint matching must preserve these transforms or explicitly decline unsupported geometry. Do not coerce arbitrary matrices to uniform scale. `SceneStateInfo.getPositionMatrix(1)` alone is not the actual draw matrix: native drawing uses its current drawing ratio and composes the snapshot rotation. Reading a shader after a later frame can also see a reset identity matrix; a finished adapter must capture the actual draw event rather than assuming that cache is stable.

## Replacement kernel

`RecentsMotionPath` holds app-content center, uniform scale and unwrapped angle in a stable natural-portrait basis. One gesture fraction drives all components; reverse motion retraces the same path. Similarity transforms preserve aspect ratio and avoid singular matrices during a quarter turn. An explicit verified leash-to-portrait transform projects the same pose into the current Surface basis. Display rotation alone cannot stand in for that transform because Shell may insert fixed-rotation parents.

Nine pure geometry tests cover endpoint matrices, reversal, all display rotations, shortest-angle interpolation, inverses, continuity, no singular mid-turn and invalid/skewed/reflected geometry. These tests validate the kernel only, not integration with Samsung animation.

The runtime adapter still needs to bind the exact closing app leash, native crop, actual thumbnail content transform, overlay viewport, progress and cancellation in one session. The overlay and app must share a pose before handoff. A generic SurfaceControl hook or replacing an arbitrary `UpdateInfo` would affect unrelated app transitions and is not acceptable.

## Local measurement build

Normal builds leave tracing disabled. Build a signed local release with `-PrecentsMotionTrace=true` to enable `RecentsMotionTrace`. It validates the installed DEX call path and captures at most 64 geometry records per session, sampled by native events, without polling. It modifies no animation arguments, return values, View properties or Surface transactions. Trace failure disables tracing, not unrelated hooks. Turn the Recents portrait option off to stop collection, or install a normal build.

Tags: `RECENTS_MOTION_DEX_VERIFIED`, `RECENTS_MOTION_BEGIN`, `RECENTS_MOTION_FRAME`, `RECENTS_MOTION_TRACE`, `RECENTS_MOTION_TRACE_DISABLED`. Logs contain geometry, not screenshots or app text. Private screen recordings and database backups remain excluded from Git.

## Rejected candidates and pending checks

- Decor alpha, window alpha, pre-draw suppression and jump cuts are not used.
- Selecting the native Recents-first branch alone did not demonstrate a seamless transition and has been removed.
- Early orientation changes to the separate overlay alone did not demonstrate a seamless transition and have been removed.
- Fullscreen launcher gestures, reverse/cancel, quick Home, task launch, repeated landscape rotations, feature OFF, three-button navigation, PiP, split screen and process recovery still need validation with the finished adapter.

Primary reference for coordinate-space handling: [AOSP TaskViewSimulator](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/quickstep/src/com/android/quickstep/util/TaskViewSimulator.java). It explicitly separates task, Launcher and display coordinates and retains the gesture-start rotation for Shell transitions. Samsung uses a different implementation; this is a design reference, not evidence that the AOSP class is loaded here.

## Input and display-change path for the replacement adapter

Further inspected native code: `AnimationSessionKt.displacement` calculates deltas in captured region axes. `AnimationSessionKt.obtain` separately repositions the event passed directly into the gesture cards. Both paths need consistent coordinates; rotating only the View does not transform this direct input dispatch.

`AnimationSession` also observes `DeviceState`. Its typed FlowCollector compares the new display size with `LeashSizeCalculator.getFullDisplaySize()`. During a gesture, a mismatch can dispatch `ActionEvent.StartHomeScreenAnimation`. This is a verified code path, not yet a proven explanation for every failed earlier test. Simply requesting portrait early can therefore change gesture completion behavior.

`RecentsGestureCoordinates` captures the initial display transform and pointer, projects later events back into those original axes, and produces displacement plus a virtual portrait-card pointer without accumulating deltas. Six additional tests include the actual measured landscape drag, both landscape rotations, repeated rotation/reversal, horizontal drift, native side-region conventions and invalid inputs. This component is now connected only by the opt-in experimental adapter described below; the normal APK retains native input.

The adapter attempts one coordinated session: capture the source gesture; prepare a portrait overlay; rebase only its own pending display-size change before the native mismatch fallback; project both displacement and direct View events; map card target bounds through the verified player Home-to-window basis; convert spring callbacks back into card coordinates; and preserve native completion/cancellation. General rotation changes, Home gestures, DeX, split screen and unrelated tasks must retain native behavior. This is not a plan to hook all MotionEvents or globally suppress the native fallback.


## 2026-10-18: experimental adapter trials and rollback

`RecentsCoordinateHooks` is installed only with the separate build property `-PrecentsCoordinateExperimental=true`; its default is false. It binds a fullscreen display-0 app task to its exact `AnimationSession`, `TaskViewInteraction.leashOverlayWindow` and native player. It excludes Home/lock/key starts, re-entry with an existing player, DeX, non-fullscreen apps, secondary displays and unmeasured input regions. It does not hook SystemUI or modify app orientation. Existing native spring, cropping, alpha, Surface transactions and completion are retained.

The typed `DeviceState` and `WindowManager.LayoutParams` FlowCollectors are found using captured owner type, payload signature and DEX call relationships. Their compiler-generated class names are not hardcoded. The installed test APK verified 27 coordinate-path callers. A whole-event matrix transforms cloned MotionEvents, including pointer history; individual point edits would leave old historical samples in the wrong axes.

Two signed **local experimental release builds**, not public releases, were tried within an explicitly authorized device-use window:

1. Shell supplied an already-normalized 1440 x 3120 closing source with an identity player basis, even though the initial gesture was 3120 x 1440 / rotation 1. A guard that compared player bounds with the *gesture start size* rejected it. The feature stopped and returned to native requests. The app target must instead be matched by task ID and actual remote source-container bounds.
2. After correcting that check, the adapter bound a 3120 x 1440 app with a quarter-turn player basis. However, the gesture overlay remained **3120 x 1440 / rotation 1** while the calculator had been changed to 1440 x 3120. At handoff, measured card scale was about **1.5249 x 0.3450**, and the mapped target extended to y approximately -3121. The card did not meet the final portrait card. This is a failed experiment, not a seamless transition. The native Recents-first branch was selected, so selecting that branch alone is insufficient.

Screenrecord letterboxed landscape content into a fixed portrait recording. Those black recording margins are not evidence of deliberate visual masking. The window geometry and unequal native View scales independently establish the mismatch. Videos, screenshots, original logs and app/task identifiers remain private and excluded from Git.

The phone was restored to the normal signed release build with **both experimental and tracing flags false**. The Launcher was reloaded and its normal hook initialization confirmed; the private orientation probe was uninstalled and the test recording files removed from the phone. No device reboot or SystemUI reload was performed in these trials. Cancellation/reversal and seamless completion have **not** been validated for this adapter.

### Source changes after these failed trials (not device-tested)

- Defer calculator and pointer changes until player binding. Requesting portrait is preparation, not proof that native layout became portrait.
- Validate the **owned overlay's actual measured dimensions** and the calculator's native `RecentStyleData.getBounds()` against the same portrait viewport before enabling the adapter; recheck before mapping a card target. A stale landscape styler or dormant 1 x 1 window is rejected.
- Rebase from the actual native player's current rectangle after verifying source identity/basis; discard old pending target rectangles rather than interpreting them in new axes.
- Resolve the overlay through the session's own `TaskViewInteraction` instead of changing every remembered overlay on display 0.
- Keep a session snapshot across native synchronous invalidation so error/disable cleanup can restore the original calculator size and drawn bounds.

These checks prevent the *known* mixed-viewport state from being accepted. They do not arrange the missing portrait overlay/styler, and they are **not the animation fix**. The next design must render the gesture cards and live app in a common verified viewport throughout the transition, including when Shell defers display rotation, before enabling coordinate projection. Continuity must be established at the actual snapshot-content handoff, not only the outer card rectangle.

The native calculator calls `getStyleData()` for scene insets, bounds and list scale, while `calculateTopRect` also reads its mutable `fullDisplaySize`. Therefore changing that Point does not update all of its inputs. `LeashOverlayWindowImpl` updates its cached styler from its own WindowContext on configuration/inset events; the final Activity uses a different styler. These verified source paths explain why the earlier Point-only rebase was insufficient. They do not establish when Shell will honor an orientation request.

Four regression tests use the measured portrait/landscape dimensions, stale styler, incomplete layout and invalid geometry. Together with input projection and existing tests, **65 unit tests** pass. Signed normal APK assembly and release Lint pass. The post-trial guard itself has not been loaded on the phone.


## 2026-10-18: Home Up compatibility requirement and subsequent experiments

The user confirmed that **card order/layout must follow Home Up**, with Task Changer both enabled and disabled. Do not replace the selected layout with a fixed carousel or change Home Up preferences as a side effect. Validate both configurations before claiming compatibility. New Home Up/Launcher versions require fresh method, geometry and lifecycle checks; version equality alone is not sufficient.

The earlier Point/input rebasing prototype has been removed from `RecentsCoordinateHooks`. The current experimental adapter stamps native `TaskViewInfo` with its actual producing View's window space and maps observed destination rectangles into the unmodified native gesture viewport. The producer is resolved by DEX construction/call relationships rather than its obfuscated class name. `TaskViewInfo` mutates maps after construction, changing its hash; a bounded weak **identity** cache is necessary. The source root and the final Recents Activity can differ even when both exist. Blindly treating every rectangle as coming from the final Activity caused a double quarter turn.

Actual portrait card rectangles reduced the extreme anisotropic deformation in one trial, but the native card selection/layout changed after the initial measurement. The resulting transition still did not meet the final card. This adapter remains **unfinished and disabled in normal builds**. Four window-space and three identity-cache regression tests increase the pure test count from 65 to 72; they do not validate the gesture visually.

Module updates are observed asynchronously by the installed Vector environment. Some immediate Launcher reloads retained older code despite matching the installed APK hash. The private test harness now waits after installation and verifies a fresh process's build/implementation tag before injecting a gesture. Earlier trials without that evidence cannot be assumed to have exercised the latest candidate.

### AOSP-style live tile investigation

The requested behavior is continued drawing of the **last app** in its Recents card, following AOSP. It is not a request to resume every historical task or introduce background audio playback. App playback policy is not overridden and unrelated audio is not muted.

Primary source, pinned Android 16 Launcher3 revision: [RecentsView.java](https://android.googlesource.com/platform/packages/apps/Launcher3/+/2f5b9b869d86620fdb899bc2df53d523dc9dc6ea/quickstep/src/com/android/quickstep/views/RecentsView.java). AOSP retains the remote animation targets/controller and applies its task simulator while drawing/scrolling. Keeping a process alive alone does not implement the visible live tile.

Verified Samsung methods: `AnimationSession.requestFinishRecentAnimation()` skips the native finish when both `willGoToRecents` and `dontFinishByCommand` are true. The native debug `--liveTileOn` switch only sets this hold for future sessions; it is insufficient as a finished feature. Selecting the native Recents-first transient branch and holding one owned session kept a private, permission-free animated probe drawing after Recents entry. Card images remained snapshots. Native input flow also depends on transition state, so retaining the controller requires checking input routing, launch, Home, cancellation, screen off and cleanup rather than merely suppressing a finish method.

`RecentsLiveTileHooks`, `RecentsLiveSurface` and `RecentsLiveBlur` are **local experiments**, installed only by `-PrecentsLiveTileExperimental=true` (default false). Normal release builds contain no active live-tile hook. No dedicated public setting is exposed yet. The prototype is tied to the master/Home scope switch, restores the native hold on invalidation, and calls native completion when unsupported or when Recents stops/screen turns off. It does not suppress app lifecycle callbacks.

The first surface trial used the exact native `TaskSceneView.draw` foreground shader, native crop path and actual View-to-global matrix, with one task-ID-matched closing leash below Recents. Native drawing clears only that card's path so other overlapping cards can still occlude it. The app was genuinely present in SurfaceFlinger and drawing, but it was blurred by Samsung's full-screen **250-radius blur region** on the Recents window. Thus a transparent card alone is insufficient. The next local trial excludes only the card path from the owned native backdrop's blur, restoring its previous path on cleanup. This is not a verified feature yet.

The surface binding currently deliberately accepts only a measured portrait viewport with matching remote source dimensions and identity native player basis. Landscape, source-parent conversion, input cancellation, replaced/unbound cards, feature OFF, process recovery, three-button navigation and both Home Up configurations remain required before enabling it for users. Private recordings/decompiled sources and user task lists remain excluded from the repository.


### Live surface results: portrait only, still experimental

With Task Changer enabled (Tilt stack), two separate device screenshots showed the running probe's counter advancing from 3203 to 3462 with the moving dot at different positions, while its native card shape, overlapping neighboring cards and wallpaper blur remained intact. Applying the existing `BackgroundBlurDrawable.setClipPath` to exclude only the native card path resolved the measured blur of the live app; original backdrop clipping is restored on release. This verifies visible continued rendering for that portrait test, not seamless landscape entry.

A native task-launch attempt resumed the underlying app but left the retained Recents controller showing its card. The prototype now releases through `RecentsAnimationManager.finish()` before `ShellTransitionManager.getActivityLaunchOptions(Info,ShellAnimationRunner)` for a launch whose target belongs to the owned Recents root. A subsequent device test returned to the fullscreen probe and logged native invalidation. Home also released the held session. Input redirection experiments were removed: the required correction was transition completion, and no generic input hook is retained.

Task Changer was temporarily disabled through Home Up's own switch; its persisted setting was read back as false. The portrait live surface also rendered in that configuration, with native default large card icons and hidden app labels instead of the enabled configuration's app labels. User-selected layout values were not overwritten. Native geometry is read at draw time, including small independent scales and Home Up shear, rather than forcing every style to a similarity matrix. A matrix cache prevents a stable card from scheduling a new Launcher frame continuously; app buffers keep updating through the compositor. The native Surface transaction applier is registered as a target release check.

Four additional geometry regression tests cover reduced-resolution snapshots, retained native shear/rounding, landscape snapshot rotation inside a portrait card, and rejecting stale/cropped/perspective inputs. **76 unit tests and release Lint passed** after the constant correction. This verifies geometry calculations, not the remaining device lifecycle cases.

The latest source additionally rejects different-user tasks, locked/redacted/non-real thumbnails, and a recycled scene that no longer owns the closing task ID. These checks must be confirmed on the next loaded build. Android versions other than SDK 36 are rejected pending renewed validation. Continued work remains: landscape source-parent conversion and continuous entry, cancellation/reversal, screen off, reused/recycled cards, per-feature setting/status UI, feature OFF and recovery. Do not publish a release claiming these are finished.


The Home Up Task Changer switch was restored to **on**, with Tilt stack (layout type 5) and “center running app” off unchanged, after the off-state trial. The latest full experimental assembly, 76 tests and release Lint pass.

A subsequent landscape trial bound a 3120 x 1440 closing app to the portrait Recents root using the native player's measured `[0 1 0; -1 0 1440; 0 0 1]` basis. That basis is now checked against both observed window spaces before applying the actual snapshot shader; display rotation alone is still not accepted as sufficient evidence. The retained transient transition leaves the physical display in landscape coordinates while the Recents window has a fixed portrait configuration. A session-owned native input listener prototype therefore maps complete MotionEvents (including history) from the current physical display space to the measured Recents root. Its exact original listener is restored by identity before native completion; there is no global MotionEvent hook. Validation of that input path is pending.

The live card's pre-draw check now stops the experiment if its View is detached/recycled for another task or its viewport changes. Real-snapshot and unlocked-card checks prevent replacing native redaction with live content. The remaining landing transition still uses Samsung's native gesture card animation and has not been fixed by the live-tile work. These partial results must not be presented as a seamless portrait gesture fix.


### Input evidence and current 3D-layout limitation

The session-owned input-listener experiment was removed after tracing the real card View: InputDispatcher already delivered correct local portrait coordinates, while `getRawX/Y` remained in physical landscape axes. For a horizontal card swipe, the actual trace had local `(1200,1550)` to `(400,1550)` but raw `(1550,240)` to `(1550,1040)`. Samsung's native card gesture code uses raw deltas for direction, explaining the mismatch. The prototype now rebases a cloned event only at the owned native card list during a retained session. It preserves local coordinates, all pointers, historical samples, times, classification, pressure and tool metadata, and recycles the copy after dispatch. There is no global input listener or raw-input-device injection.

[AOSP Android 16 Input.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/libs/input/Input.cpp) distinguishes changing the local transform from changing stored samples. A private on-device instrumentation test verified the combined rebase with two pointers, two historical samples, a scaled/translated parent and classification metadata. The test process needed its instrumentation-only `--no-hidden-api-checks` flag to access the same hidden method that the existing hooked Launcher environment exposes; no global hidden-API setting was changed. All those native checks passed.

A real card swipe subsequently reached the new axis correction, but Tilt stack introduced a **perspective matrix** during motion. The compositor's ordinary app leash transform cannot represent that projective View transform. The current experiment rejects it, restores the original backdrop/card drawing and completes the native transition. Thus live rendering currently ends on that unsupported motion; it is **not complete Home Up compatibility**. The steady portrait on/off rendering results remain valid, but do not imply continued live rendering through every Home Up animation. Source, input, perspective handling and continuous entry still need a coordinated solution. Normal builds keep all these experimental paths disabled.


## Common portrait viewport prototype (stages 1–9, 2026-10-18)

The old `TaskViewInfo` map adapter has been replaced by `RecentsViewportHooks` and `RecentsViewportMotion`. This is still gated by `recentsCoordinateExperimental` (default **false**). Stage 1 measured when a matching portrait scene became available; subsequent stages use a critically damped pose to move the exact closing app leash and its matching native thumbnail together. The target is the native foreground shader composed with its actual View-to-window matrix, including snapshot rotation and resolution. `RecentsLeashSpace` resolves a source that Shell has already normalized to portrait as well as a real landscape source, using the measured native player basis instead of the task's stale rotation flag.

The transition does not make a window transparent or suppress a pre-draw frame. A native handoff can be deferred only while the owned app and card continue rendering toward the measured destination, with a two-second failure limit. The source pose is retained for at most 34 ms while the first destination scene is recorded; otherwise the native pose is used. Native invalidation or feature OFF releases owned state and restores the prior hold. Combining this prototype with the live-tile flag is deliberately rejected until their Surface ownership and completion lifetimes have been coordinated.

`RecentsChase` is an analytic critically damped spring over center, logarithmic scale, angle, crop and radius. Its five tests check velocity-preserving retargeting, reversal, frame-rate independence, convergence and rejection of invalid state. Five additional `RecentsLeashSpace` tests cover normalized source bases, real quarter turns, invalid bases, landscape snapshots inside portrait-normalized sources, and bounded pixel error. The total pure test count is **86**; these tests are not visual verification.

The replacement card list records the native portrait list and replays it in the same moving viewport as the live app. It preserves the selected Home Up layout and the native ordering. Own `RenderNode`s must have an explicit position as well as recording dimensions (the Android SDK source documents that these are separate). `ViewGroup.dispatchDraw` has already applied the source list's own scroll translation, whereas `transformMatrixToGlobal` excludes self scroll: the replacement cancels that translation exactly once. A measured scroll origin exceeded 150,000 pixels; failing to cancel it made a seemingly valid replacement draw outside the visible clip. The one `ViewGroup.dispatchDraw` hook is scoped by verified gesture-list class, owned root identity and a live session; it changes no other View's draw.

Stage 6 had an initialization error that stopped the Recents hook through its failure handler, without an uncaught Launcher crash. It was corrected before subsequent trials. Stages 7–8 verified the replay callback executing on the device; node bounds and self-scroll handling were corrected. Stage 8's recorded replay contains surrounding cards rather than an empty area. The recorded source app's circle remains circular during the measured motion, and the destination card ordering now matches across handoff. This does **not** yet prove the entire physical display rotation is perceptually seamless. A fixed-orientation screenrecord changes its letterboxing when display rotation completes, so that recording alone is insufficient to establish physical rotation continuity.

The stage 8 reverse gesture returned to the still-rendering landscape probe with its original orientation; no portrait motion was started on that path. Cancellation, quick Home, Task Changer OFF, rapid interruption and the live-tile combination remain separate checks. No public release or dedicated live-tile setting has been created for this prototype.

### Root cause of the Home Up card-selection jump

Verified native chain:

1. `RecentInsetsManagerImpl.updateInsetsData` writes the display's shared insets state. `SceneOnlyPortCalculator` rotates cached portrait scene insets into landscape axes when physical display rotation is 1 or 3.
2. `RecentStyler.createStyleData(Context)` reads that shared state's `getInsetsDataState().getValue()`, including when its Context is the portrait `RecentsActivity`.
3. During a landscape gesture the Activity still had 1440 × 3120 bounds but received scene insets `(140, 0, 43, 0)`. Its scene was consequently 793 × 1969 rather than the later native portrait 909 × 1853 scene.
4. `StackLayoutManager` retains an integer pixel scroll offset while its `l()` card-width calculation changes. In the measured case, an unchanged offset of 34,892 moved the calculated center from adapter position 1 to 6 when portrait insets were restored. The task list was not reordered by our code; its scroll coordinate had changed meaning.

`RecentsViewportInsets` supplies a window-local value only during the portrait Activity's synchronous style calculation. It reads that Activity's current `WindowMetrics`, validates portrait bounds and portrait system/cutout edges, then uses the existing native standard/scene/stage masks and scene/layout calculators. A temporary StateFlow is returned only for that synchronous read. The shared repository, gesture-window insets and native caches are not modified. Missing methods, unsupported geometry or insets still expressed in landscape axes use native behavior. This changes geometry, not status bar colors or visibility.

After this correction, separate device trials recorded identical task IDs and card centers at enter/handoff and 100, 400 and 1,000 ms after handoff. The closing card and the previous app retained their expected positions. Keep this correction independent of any future compositor/live-tile integration and validate it with both Task Changer states and updated Launcher/Home Up builds.


Stage 9 also ran after Task Changer was switched OFF through its own UI and its switch state was read back as false. The new app/list motion ran, and the card IDs and centers at handoff remained the same at 100, 400 and 1,000 ms afterward. Native default icon/label behavior was retained. Task Changer was subsequently restored to ON through the same UI and verified. An earlier trial named “off9” did not actually switch it off because the display rotated between obtaining coordinates and tapping; that trial is not off-state evidence. The private switch helper now re-reads the current UI before tapping, and dependent tests stop if switch verification fails.


## Coordinating continuous motion and live rendering (stages 10–16)

The two private flags can now be combined. `RecentsHandoffGate` transfers one manager's Surface ownership after the portrait viewport has landed, or reports failure/cancellation to the waiting live renderer. The viewport's own hold, node recording and matrix overrides are relinquished before the live renderer takes over. Five pure tests check transfer exactly once, stale tickets, identity despite mutable/equal native owners, reentrant callbacks and cleanup when a continuation throws. A native request arriving after handoff binds immediately; an earlier request waits for the owned handoff. This prevents two independent code paths from writing a retained app's matrix.

A combined landscape device trial logged a real 3120 × 1440 source, the verified quarter-turn source basis, settled viewport handoff and the same native thumbnail-content matrix being applied by the live renderer afterward. The private animated app continued drawing after entry and after scrolling its card into the center. Separate screenshots showed different frame counts and dot positions. This establishes visible continued rendering in that trial; it does not validate all apps, playback policies or lifecycle cases. No app's pause/resume callbacks or audio policy are overridden.

A projective card is now a distinct unsupported **visual** geometry. The intended fallback retains the native controller and app drawing, restores native backdrop clipping, hides only the owned app leash and lets Samsung draw its normal snapshot during the projective portion. Once affine native geometry returns, the live hole and Surface return. A similar event-driven suspension handles a recycled/offscreen card and searches the owned native root for the same task when it returns. These changes need explicit perspective/recycle trials before claiming they are verified. App lock/redaction, missing native data, changed viewport or invalid leashes still end the experiment safely.

During investigation, some neighboring thumbnails appeared sideways. Their actual snapshot dimensions and rotation metadata showed they were correctly captured landscape apps, while portrait app snapshots remained portrait. Context and root both reported rotation zero in that window. A speculative additional snapshot-rotation correction was therefore removed; it is not part of the implementation. Native snapshot rotation is preserved. Private card metadata and pictures are not published.

The native recording may letterbox a landscape display into a fixed portrait encoder canvas. For physical-orientation review, an all-landscape part of that recording can be cropped to its active letterbox and quarter-turned into natural portrait coordinates. This avoids confusing the encoder canvas's orientation with the physical card geometry. Such review showed the closing app moving into its native off-center portrait card while the circle stayed circular and the surrounding native cards appeared. The exact incoming velocity and rapid post-release interruption still need review; do not claim universal seamless motion from a contact sheet alone.

Unused source-cache/viewport-contract utilities and their seven tests from abandoned coordinate adapters, plus their unused DEX producer finder, were removed. They are not runtime compatibility guards for the new controller. The test total is now **84** including the five handoff tests; earlier totals in the chronological investigation remain historical. APKs, DEX, private recordings and task IDs remain excluded from Git.


### Later checks and private settings

In the stage 16 device trial, six controlled horizontal swipes moved through the native stack and back. `RECENTS_LIVE_NATIVE_PERSPECTIVE` was followed about 28 seconds later by `RECENTS_LIVE_RESUMED`; the private app's draw counter continued throughout and afterward. The same native task returned to live drawing. This checks temporary native snapshots during the selected Tilt-stack movement, not every possible layout or recycling pattern.

A separate stage 17 probe used `WindowManager.LayoutParams.FLAG_SECURE`. Samsung supplied a small app-theme thumbnail with `isRealSnapshot=false`. The live renderer declined binding, no live Surface draw was logged, the native controller finished and the probe paused/stopped. Protected/non-real and app-locked thumbnails are now checked before constructing the motion/live renderer as well as at draw time. This is one real secure-window test; Samsung app-lock enrollment and other protected media have not been individually tested.

The private build now has a Material 3 switch and graphical status card for live Recents. The preference defaults OFF, respects the global/Home scope switches and uses event-driven native status reporting. Ready and active are distinguished, and an unavailable/failed hook is not reported as ready. The section and status card are hidden in normal builds while the private live flag remains false. The private switch was enabled and read back through the app's UI for subsequent validation. English, Japanese, Korean, Simplified Chinese and Traditional Chinese strings are included. This does not promote either experiment to a normal release.


With the private UI switch enabled, another landscape gesture exercised the combined renderer and continued live drawing. Tapping the visible edge of the running probe's card invoked the owned native task-launch path, released the controller and returned to that same fullscreen landscape app. The activity-manager result and native release log agreed. A one-shot 2.2-second main-thread deadline now complements the frame-based motion deadline, so missing Choreographer frames cannot leave the viewport's own hold waiting indefinitely. The new deadline itself has not been forced in a device test.


## Hardware video and backdrop isolation (stages 18–21)

A private, permission-free probe additionally uses a real `VideoView`/`SurfaceView` playing a local synthetic moving test pattern, with volume zero and no audio focus. With live Recents OFF the native Activity paused/stopped after entry. With it ON, both the Canvas counter and hardware video continued changing. No app lifecycle callback or playback policy is hooked. This verifies a real child Surface, not YouTube's particular playback policy.

The earlier path-only blur exclusion allowed the moving video's colors to bleed outside the card through Samsung's full-screen blur. The replacement owns one effect Surface below the live app, disables only the verified full-window native `BackgroundBlurDrawable` radii while active, and preserves the native tint/card foreground above. The effect is parented to the actual portrait `ViewRootImpl` Surface, not the remote Recents leash, whose source space was still landscape. The live app has an explicit native-card crop and corner radius. In the stage 20 screenshots the video pattern and counter changed while the colored halo disappeared and wallpaper blur remained. Unsupported/changed backdrop identities or clipping fall back to the native snapshot.

Stage 21 combined this crop/backdrop fix, the common viewport and live rendering with **Task Changer OFF**, read back through Home Up's UI. Native card icons/order/layout remained and both moving contents rendered. Home removed the owned effect Surface (SurfaceFlinger list count returned to zero); native app pause/stop resumed. Task Changer was restored to ON. Earlier ON trials also exercised perspective snapshot fallback and subsequent live resumption. User-selected layout type and center-running-app preferences were not changed.

`active` is recorded before the first native blur mutation so partial setup failure can restore the original radii. Effect removal is attempted even when restoration fails, with separate diagnostics. Navigation-mode guards accept only the verified Samsung gesture modes 2/3; unknown modes and buttons leave the gesture renderer untouched. Three-button navigation has not been switched on in the user's device for this experiment.


### Native exit paths and input ownership

A later normal-build check exposed a genuine retained-controller bug: BACK left the live effect present, and starting the manager Activity through an external intent could leave the old Recents Surface over the newly focused app. Samsung's `InputConsumerProxy.sendToInputProxy(InputEvent)` returns false for all non-MotionEvents. The module now handles only BACK events belonging to the held session's verified proxy, releases the controller, and invokes that Recents Activity's existing `onBackPressed` behavior. The device then returned to the fullscreen probe and the owned effect count became zero.

Pausing/stopping the Recents Activity alone is insufficient for external launches. During the retained **transient** animation, both the new app and Recents appeared as resumed activities; the Recents window did not pause just because the external app became focused. The implementation therefore observes the already registered native `TaskStackChangeListeners.Impl.handleMessage` path for its verified `ON_TASK_MOVED_TO_FRONT` constant. An event for another task on display 0 ends the held controller; the owned Recents task and events queued before the hold are excluded. It registers no additional task listener and does not poll the foreground app. A subsequent external manager launch removed the effect, logged `foreground task changed`, displayed the actual manager UI and allowed the Material switch to be turned off and read back.

The private harness previously used a fixed five-second Launcher startup wait. One candidate's verified hook initialization took longer, so the portrait trial stopped **before** input rather than exercising an unconfirmed implementation. A bounded wait for the fresh process's implementation tag replaced that fixed readiness assumption. The repeated portrait trial passed. This was not a Launcher crash or startup-protection event.


### Portrait navigation while the controller is retained

Holding the landscape controller leaves the physical display and native gesture-region state landscape even when the fixed-orientation Recents window is portrait. A natural-portrait bottom swipe reached the card list but did not perform Home. The bridge now uses that portrait window's actual navigation/mandatory-gesture insets (94 pixels in the measured session), one pointer, a matching downTime, native touch slop, upward motion and the native Recents cancel-area boundary. It applies only while the proxy reports landscape and the owned root is portrait. An accepted sequence cancels the list's stream and posts the existing native `KeyInjector.injectKey(HOME, ownerUser, display0)` after input dispatch. Ordinary card scrolling, taps and portrait-source sessions do not use this bridge. Missing insets or a missing native navigation owner use native fallback.

`GestureInputHandler.inputHandle(int, MotionEvent)` and `getKeyInjector()` are verified in the actual APK. Its main-display DOWN supplies the existing injector; no privileged service, new app permission or guessed obfuscated graph getter is introduced. An attempted Dagger return-type lookup found no direct getter and safely disabled the feature; that lookup was removed. `HoneySystemController.emulateHomeKeyPressInLauncher()` was also unsuitable for leaving the Recents Activity and is not used.

In the subsequent native-input candidate, a real injected bottom swipe returned to `LauncherActivity`, removed the effect Surface and logged `portrait navigation home`. Native keyboard BACK and external task launches have separate release paths as documented above. This does not validate every predictive-back or taskbar configuration.

The unused old `RecentsGestureCoordinates` adapter and its eight tests were removed during cleanup; the final current pure-test total is **76**. Historical counts above describe the earlier source states.


### Edge Back gestures

The retained landscape session also ignored a portrait-window edge swipe. `RecentsNavigationGesture` now identifies left/right Back regions from that window's `systemGestures` insets and owns the Back touch stream so the card list does not scroll underneath it. In the measured window the insets were left 87, top 174, right 175, bottom 94; dimensions are read at runtime, not hardcoded. After an accepted inward swipe is released, the same verified native Recents Back behavior is invoked. A left-edge device trial returned to the fullscreen probe and left zero owned effects.

Six pure regression tests cover both Back edges, Home's native cancel area, repeated/stale UP events, reversed/diagonal gestures, cancellation, missing regions and multi-pointer interruption. A cancelled multi-pointer Back stream remains consumed until its terminal event, without issuing navigation or delivering an orphan pointer stream to the card list. The current total is **82 unit tests**, all passing with release Lint. The bridge performs native navigation at finger release; it does not implement an additional predictive-back preview animation.


A portrait-source live session subsequently showed the same missing edge Back behavior. Window-local navigation is therefore enabled for **every owned portrait live window**, not only landscape sources. The earlier physical-orientation condition was removed. Native source dimensions/matrices remain unchanged, and sessions still require validated real navigation insets and an existing main-display native injector.
