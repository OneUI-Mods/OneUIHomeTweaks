# Changelog

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
