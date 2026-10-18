# Changelog

## 0.11.0 (2026-10-18)

- Remove portrait-only Recents and its custom rotation/viewport animation, insets override, handoff barrier and tracing implementation. Remove its switch, details and graphical status in every UI language.
- Follow Samsung’s native orientation and transitions. Ignore the retired saved preference; return OFF to older loaded hooks during an in-place update.
- Keep experimental live rendering independently in native portrait and landscape Recents, with window-local navigation in either orientation. Preserve the home-bar option and the suggested-app launch fix.
- Accept settled Home Up projection residuals only when a conservative error bound across the complete source rectangle is at most 0.5 screen pixels; keep true perspective animations on native snapshots.
- Retain the previous investigation as historical documentation.

## 0.10.1 (2026-10-18)

- Fix suggested apps at the bottom of Recents waiting indefinitely while live rendering is enabled. Release the owned controller at the native launch-request gate, before its transition-wait branch; retain the direct task-card launch path.
- Preserve Samsung's native icon hit testing, selected app, launch options and animation. Apply the handoff only to requests originating from the owned Recents root.

## 0.10.0 (2026-10-18)

- Add explicit persistence of aligned app, shortcut and folder positions through the native DIY editor, with a preview of affected items/pages and a confirmation step.
- Reject stale layout previews and confirm the resulting coordinates using a read-only database query; leave widgets, scale and rotation unchanged.
- Redesign the four-tab Material 3 manager with grouped settings, detail dialogs, immediate color choices, a theme selector and optional dynamic colors.
- Distinguish pending hook responses from crash protection; reject diagnostics from an earlier device boot.
- Allow volume theme configuration to recover after temporary provider errors and first unlock.
- Replace the mismatched landscape/portrait card transition with a common viewport for the app Surface and native portrait card list; preserve Home Up ordering and fix window-local insets that shifted the selected card at handoff.
- Add an independent, default-OFF experimental option to keep the last app drawing in Recents. Retain only its owned native controller, map the live Surface to the native card and isolate wallpaper blur below it.
- Preserve native snapshots during perspective animations and for protected/incompatible tasks; restore native state on launch, Home, cancellation, Recents exit and disable.
- Restore Home and Back gestures in the owned portrait live window, cancel invalid/multi-pointer navigation, and release retained controllers on external foreground-task changes.
- Include graphical ready/active/unsupported states and localized controls. Limit the new renderer to SDK 36, the main fullscreen display and verified gesture modes; no SystemUI or app-lifecycle hooks are added.
- Remove abandoned coordinate adapters and private build switches. Detailed card traces remain opt-in and disabled in distributed APKs.

## 0.9.0

- Add an optional normal-grid layout for DIY Home app icons, shortcuts and folders, including existing placements.
- Snap native DIY editing moves and drop coordinates to the nearest available cells; preserve subpixel pointer movement and native edit frames.
- Keep DIY Home enabled and preserve widget/sticker positions, scale, rotation and native edit history.
- Read the normal grid and per-item padding from Home Up instead of hardcoding screen dimensions.
- Keep layout projection separate from persistence; toggling alone leaves saved coordinates unchanged. User edits still use native persistence.
- Add a Material 3 switch and separate graphical status in all five UI languages.
- Fall back to native placement for unsupported geometry, preview/secondary displays, landscape, RTL, capacity overflow or hook failures.

## 0.8.0

- Add an optional date-before-time layout for the native status-bar clock.
- Preserve OS / QuickStar date visibility, native date formats, clock visibility and colors.
- Restore native order immediately when disabled; do not synthesize a date when hidden.
- Add a Material 3 switch in Appearance and a separate graphical hook status in all five languages.
- Leave unknown clock formats, RTL layouts and unverified SystemUI builds unchanged.

## 0.6.0

- Rename the app to One UI Home Tweaks and replace the wallpaper icon with a home/customization icon.
- Preserve the application ID, signing key, existing settings and Vector scopes for in-place updates.
- Add an optional portrait-only recent apps screen, including transitions from landscape apps.
- Limit orientation overrides to the verified RecentsActivity on the main fullscreen display.
- Restore native requests when disabled; respect global and One UI Home switches.
- Add a gesture-only home bar hiding option for recents; preserve three-button navigation and system-wide hint visibility.
- Add separate graphical recents hook states and localized settings in all five UI languages.

## 0.5.1

- Prevent deliberate or unclassified target-process reloads from being counted as hook crashes.
- Match Android's recorded crash reason to the exact previous process before incrementing the
  startup protection counter.
- Keep crash-protection diagnostics visible while requiring a fresh response for active status.

## 0.5.0

- Migrate the module from legacy Xposed API 82 to modern libxposed API 102.
- Register the entry point, fixed scope and protective exception mode through `META-INF/xposed`.
- Use API 102 interceptor chains and executable deoptimization for every existing hook.
- Require a modern API 102 framework; verified with Vector 2.2 on the documented SM-S948Q firmware.
- Reset crash-protection files from both credential-protected and device-protected app storage so SystemUI can be recovered from the app.

## 0.4.1

- Distribute the non-debuggable release build with a dedicated persistent signing key.
- CI verifies the unsigned release variant; it no longer offers a debug APK as the distribution artifact.
- Debug-to-release migration requires reinstalling because the certificates differ. Hook behavior is unchanged from the verified 0.4.0 source.

## 0.4.0

First public development release for the documented SM-S948Q / Android 16 baseline.

- Standard live wallpaper color hint bridge into native One UI Home label and status bar decisions.
- Independent home and drawer color modes; native drawer automatic behavior by default.
- Standard page offset notifications, including dynamically loaded Home Up free-style workspaces; remote preview workspaces excluded by real Activity window token.
- Current home wallpaper surface preview without a second live wallpaper engine.
- Optional volume-only light/dark configuration in SystemUI, including dynamically loaded Sound Assistant panels and their effect bar color model.
- Global and per-target switches, graphical hook status, root request and target restart controls.
- Host-private crash startup protection, compatibility gates and safe fallbacks.
- Material 3 bottom navigation, insets, icon, OS language following and five UI languages.
- MIT license, in-app full dependency license viewer, build-verified transitive dependency inventory.

No live wallpaper app hooks, package-specific wallpaper rules, system partition changes or device reboot operations.

Known limits: automatic colors depend on the wallpaper's own standard hints and notifications; wallpaper image count must agree with the home page count for apps that calculate slide positions from their configured image count. Android 17 / One UI 9 is disabled pending a new device investigation. See docs/testing.md for the actual verification scope.
