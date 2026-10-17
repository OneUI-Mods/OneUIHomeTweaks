# Changelog

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
