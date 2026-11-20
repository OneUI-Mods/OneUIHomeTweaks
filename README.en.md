# One UI Home Tweaks

[日本語](README.md) · English

An Android app for customizing Samsung One UI Home. Adjust home-screen and app-drawer label colors, recent apps, live-wallpaper integration, volume-panel colors, and Quick Panel dimensions. It runs as a Vector / LSPosed module.

For home-screen app names and the Finder area at the bottom, the module can inspect the background immediately around each label and choose readable black or white text. Different bright and dark areas on the same page can use different colors. It does not change status-bar colors.

**Development and physical-device validation are ongoing.** The verified baseline is Galaxy S26 Ultra (SM-S948Q), Android 16, and Vector 2.2. Installing the APK alone on an ordinary unrooted phone is insufficient. Android 17 / One UI 9 support is experimental on SM-S948Q / BZI9. Separate compatibility paths are maintained for Android 16 and 17.

Starting with 0.21.52, [Galaxy Glass Tools](https://github.com/OneUI-Mods/GalaxyGlassTools) manages the glass appearance and imports existing glass preferences once.

On Android 17 BZI9, the interaction setting briefly requests 120 Hz during Home and notification-panel interactions and after unlocking. The display setting, power-saving mode, and thermal limits still apply.

## DIY icon grid recovery

On Android 17, icon alignment retries native home-context geometry when dimensions do not fit the measured page. The viewport check remains enforced, with no changes to saved positions or widgets.

Page-size notifications also reapply alignment after the first measurement, avoiding a permanent wait when startup saw 0×0 bounds.

## Features

| Feature | Settings and behavior |
| --- | --- |
| Home-screen app names and Finder | Automatic per-location analysis / automatic wallpaper-wide colors / black / white / One UI default |
| App-drawer names | Keep stock automatic colors, or force black or white |
| Keep neighboring icons visible when closing apps | Correct Home Up's return animation when it wrongly hides home icons placed apart from the destination. Defaults ON; actual overlapping arrangements remain intact |
| Keep the last app rendering in Recents | Experimental live display of only the last app in stock portrait/landscape Recents. Defaults OFF; supports Home Up Task Changer enabled or disabled |
| Focus notifications in Recents | Experimental stock / send / do not send choices, used with continuous rendering. No per-game scopes are needed |
| Hide Circle to Search from Recents | Hide only its dedicated search task; preserve the ordinary Google app, Lens, and other apps. Defaults ON |
| Hide the gesture bar in Recents | Hide it only with gesture navigation; respect three-button navigation and the OS always-hidden setting |
| DIY Home icon alignment | Keep DIY Home while aligning only apps and folders to the normal home grid. Editing also moves them by cells. Portrait only; defaults OFF |
| Return after launching from a folder | Choose whether an upward home gesture returns to the folder. Preserve the folder-closing animation even after a fast, high upward swipe following that return |
| Live-wallpaper page notifications | Notify the wallpaper of home-page position, including Home Up freestyle mode |
| Wallpaper and style preview | Show the live wallpaper currently drawn on the home screen |
| Quick Panel | Android 16-style shared backgrounds; separately adjust brightness/volume bar thickness and dark/light-mode and sound-mode button sizes. Range 25–200%; 100% is the selected design's default. Includes 110%/105% old-design presets and an embedded stock-component preview (normal design still under validation). Defaults OFF |
| Developer animation scales | Enter window, transition, and animator scales in 0.01 increments, with plus/minus buttons, a slider, and reset. Targets Android 17 / One UI 9 BZI9 |
| Volume panel | Follow system / light / dark, including Sound Assistant's additional panel |
| Status-bar date | Move an already-visible date to the left of the clock. Visibility and format follow OS / QuickStar settings. Defaults OFF |

Configure DIY Home alignment in **Home** with DIY Home enabled in Home Up. It uses the normal home-grid settings, while widgets and stickers retain free placement. Toggling alignment itself does not rewrite saved positions. **Save aligned layout** shows the affected item/page counts before confirmation; saved positions then remain even without root or hooks. Apps and folders moved while alignment is enabled are saved by the original editor, so disabling alignment does not undo those edits. Landscape and RTL layouts use stock free placement.

If alignment dimensions do not match the current home screen, icons and folders use their original saved positions to avoid overlap. The alignment switch stays on, and no layout is automatically saved.

**Home → Layout protection and diagnostics** prevents DIY Home positions from being saved using temporary rotation dimensions. Stock layout correction proceeds only after the launcher returns to the foreground and dimensions stabilize. Widget sizing remains with the original drawing code. This protection targets Android 17 / One UI 9 BZI9, One UI Home 18.0.07.53, and Home Up 18.0.00.36. Protection and logging have separate switches. Previously saved incorrect positions are not guessed or automatically restored.

Diagnostic logs save orientation, dimensions, before/after layout changes, home-operation types, and launched apps locally, up to approximately 4 MB. They contain no screenshots or typed text and are not automatically uploaded. After a problem, export them with **Share diagnostic log**. Reload only One UI Home after updating. Existing Android 16 features remain supported.

Enable continuous rendering in the **Home** tab. It targets supported Android 16/17 gesture navigation in portrait and landscape, using One UI's stock orientations and transitions and displaying the last app live in the destination card. Cards undergoing perspective transforms temporarily use the stock snapshot, then return to live display. Protected screens and unsupported configurations retain stock behavior. App video-playback and audio policies are unchanged, so continued playback is not guaranteed for every video app.

Master and per-target switches control features. Color choices apply immediately; persistent layout saving uses a separate confirmation button. Material 3 bottom navigation divides the app into **Home**, **Display**, **Status**, and **Settings**. Icons and badges show application status, and targets can be reloaded from the app.

The UI supports Japanese, English, Simplified Chinese, Traditional Chinese, and Korean, initially following the device language. App-specific language selection, light/dark themes, and dynamic colors are supported.

After boot, initialization of launcher, clock, volume, Quick Panel, Settings and wallpaper-preview features waits for the first user unlock. Initialization runs once, without polling, and a normal later screen lock does not stop the features. This avoids leaving hooks inactive after attempting to access unavailable credential-encrypted settings or DexKit caches.

## Requirements

- A supported Samsung One UI device. Physical validation covers SM-S948Q on Android 16 / One UI 8.5 (AZHL) and Android 17 / One UI 9 (BZI9).
- An Xposed environment supporting modern libxposed API 102; Vector 2.2 was used for validation. Legacy API 82 environments cannot load the module.
- Wallpaper features require a live wallpaper. Recents features also work with static wallpaper. There is no implementation specific to a particular wallpaper app.
- An existing root environment is required to reload target processes or reset startup protection.

Root permission is requested when the app opens. Settings remain usable without granting it, but in-app reloading and related functions are unavailable. The app does not root the device or unlock its bootloader.

The former name was One UI Live Colors. Version 0.17.0 changed the package to `dev.ryuya.oneuihometweaks`. It is a separate app from the old version, so migrate settings before uninstalling the old package. After confirming migration, restart the system once to switch system-side identifiers as well. The distribution signing key is unchanged. See the [migration guide (Japanese)](docs/package-migration.md).

## Installation and setup

1. Install the APK distributed in [Releases](https://github.com/OneUI-Mods/OneUIHomeTweaks/releases).
2. Enable One UI Home Tweaks in Vector and select only the targets needed for your chosen features from the table below.
3. Open the app and grant its request through your existing root manager if needed.
4. Reload the affected apps after initial installation or an APK update, using the in-app buttons or equivalent. Reloading SystemUI may temporarily close the screen and return to the lock screen.
5. Check target responses under **Status**, then choose label colors and display settings. Ordinary color/switch changes do not require reloads.

| Features | Vector scope |
| --- | --- |
| Home/drawer colors, page notifications, DIY Home alignment, continuous Recents rendering, hidden Recents gesture bar | One UI Home (`com.sec.android.app.launcher`) |
| Quick Panel, volume panel, date to the left of the clock | System UI (`com.android.systemui`) |
| Developer animation-scale input | Settings (`com.android.settings`) |
| Live-wallpaper preview | Wallpaper and style (`com.samsung.android.app.dressroom`) |

Home, wallpaper, and panel features do not require adding the live-wallpaper app, Home Up itself, or Settings to the scope. If Vector is not applied, the app reports an unconfirmed state rather than claiming success.

Recents focus-notification adjustment additionally uses Android System (`system`), without per-game scopes. It must be manually enabled in the app after first loading and after every system restart. Whether a notified app stops audio depends on that app. See the [design, constraints, and validation (Japanese)](docs/recents-focus.md).

Distributed APKs are release builds signed with a dedicated key. Early debug builds have a different signature and must be uninstalled before installing a release build. Unsigned GitHub Actions artifacts cannot be installed directly.

## Fine-grained animation scales

On Android 17 / One UI 9 BZI9, add Settings to Vector's scope and reopen Settings. Enable **Developer animation scales** in this app's **Display** tab, then open the window, transition, or animator item in Developer options.

Enter 0.00–10.00 in 0.01 increments, or use the plus/minus buttons and slider. Lower values are faster; 0 disables animation. **Default** resets the input to 1.00, and **Save** applies it. Canceling or going back changes nothing. The three values are saved independently and the list shows the actual scales.

Disabling this feature restores the stock selection dialog while preserving saved scales. Disabling Developer options itself follows the OS's normal reset behavior. Existing Android 16 features remain, but this input dialog does not yet support Android 16. No device or SystemUI restart is needed.

## Before use

- **Automatic per location** examines the live wallpaper behind individual labels. It reevaluates when Home becomes visible, pages move, layouts change, or wallpaper-color notifications arrive. Wallpaper-wide color metadata is not required. If capture or coordinate mapping cannot be verified, stock colors are used. Background images are processed in memory, never saved or uploaded. New labels briefly fade in after background analysis; failed analysis falls back to stock colors.
- There is no constant polling. If wallpaper changes without sending a notification, colors may not update until the next page movement or similar event. Per-frame video analysis and widget/folder-interior backgrounds are excluded. The older wallpaper-color automatic mode still requires wallpaper notifications and color metadata.
- Status-bar color overrides were removed; use another module or One UI's own behavior.
- For wallpapers with one image per page, match the wallpaper's screen count to the actual home-page count. Four home pages with three wallpaper pages, for example, may show an intermediate image position.
- Preview backgrounds show the current home wallpaper, but overlaid icon positions follow Samsung's stock preview layout.
- Enable continuous Recents rendering and gesture-bar hiding separately in **Home**. Samsung's orientation and transition handling is preserved. Multi-window and external displays are excluded. Gesture-bar hiding never hides three-button controls or changes the OS always-hidden setting.
- Lock-screen label colors, wallpaper images, and system partitions are unchanged. Alignment is saved only after confirming **Save aligned layout**. Stock layout correction waits until screen dimensions stabilize.
- Features stop when compatibility cannot be confirmed after an app or OS update. Future-update compatibility is not guaranteed.

## Disabling and troubleshooting

First turn off the master or affected-target switch. To remove the module fully, disable it in Vector and reload the targets.

Startup protection disables hooks per target after repeated unstable starts. Resolve the cause before resetting it with **Reset crash protection** in settings. This does not prevent all Vector failures or every boot loop.

Send suggestions and problems to [Issues](https://github.com/OneUI-Mods/OneUIHomeTweaks/issues), including device model, Android / One UI and target-app versions, and reproduction steps. Do not publicly upload unredacted private logs or wallpaper images.

## Development

Build instructions and research materials are in the [developer guide (Japanese)](docs/development.md).

## License

Original code uses the [MIT License](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) and **Licenses** in the app for dependencies.

Quick Panel settings are under **Display → Quick Panel**. A separate preview screen configures four items; reopen the panel to apply changes. Device brightness and volume are not changed. Disabling the feature or resetting to standard sizes restores stock dimensions. Reload SystemUI after the initial update.

On Android 17, **Android 16 design** groups the bars and adjacent mode buttons in shared backgrounds. A size of 100% means the selected design's standard size. Separated layouts or excessively large combinations retain stock presentation, and editing uses the stock screen.

## Compatibility after updates

After a target-app update, open this module once to finish preparation, then reopen the target. Discovery results are saved in each module's own internal app data and reused while unchanged. No other OneUI Mods app is required. Features stop applying when a unique compatible target cannot be confirmed. Compatibility with every OS or app update is not guaranteed.

Unnecessary View retention after label-color fades has been removed. See the [0.20.4 behavior and validation record (Japanese)](docs/memory-0.20.4.md).

[Build environment / ビルド環境](docs/build-environment.md)
