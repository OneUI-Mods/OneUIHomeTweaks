# Portrait Recents: motion adapter investigation

Status: **incomplete**. The final Recents screen can be portrait, but the landscape-to-portrait gesture is not yet seamless. A development-only adapter has been connected to native input and player targets, but two device trials failed. Normal builds do not install it. Do not release this work as an animation fix.

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
