# Changelog

## 0.19.1 — 2026-10-28

- 0.19.0で、横向きの履歴画面から戻る途中にウィジェットが大きくなる不具合を修正しました。追加したウィジェット寸法の再適用処理を取り除いています。
- 配置補正は、ホームが前面でフォーカスを持ち、履歴画面が閉じてから進めます。描画監視からの不要な配置補正も取り除きました。
- ログ保存は継続します。0.19.0を使用している場合は上書き更新し、One UI Homeを読み込み直してください。

## 0.19.0 — 2026-10-28

- 対応するAndroid 17環境で、回転途中の寸法によるDIY Homeの配置ずれを防ぐ保護を追加しました。
- レイアウト安定後にウィジェットの寸法を再適用します。
- 配置変更とホーム操作の診断ログを端末内へ保存し、共有・削除できる画面を追加しました。保護とログ保存は個別に切り替えられます。
- 更新後はOne UI Homeを読み込み直してください。Android 16の既存機能は継続します。過去に保存された位置変更は自動で戻しません。

## 0.18.1 — 2026-10-27

- DIY Homeの整列中にアイコン・フォルダだけが詰まる問題を改善しました。
- 整列用の寸法が現在のホームと合わない間は、設定と配置データを保持して純正の保存位置を表示します。
- 上書き更新後はOne UI Homeを読み込み直してください。

## 0.18.0 — 2026-10-25

- Android 17 / One UI 9 BZI9の開発者向けオプションで、3種類のアニメーション倍率を0.01刻みで設定できます。
- 数値入力、±0.01、スライダー、標準値への復帰を追加し、保存・キャンセルを分けました。
- 一覧に実際の倍率を表示します。機能OFFでは保存値を維持し、標準UIへ戻ります。
- Vectorの対象へ「設定」を追加し、設定アプリを開き直してください。既存設定は引き継ぎます。


## 0.16.0

- フォルダから起動したアプリのジェスチャー終了時に、フォルダ内へ戻るかを選択。
- 復帰後のフォルダ閉じ描画を最新の縮尺から準備し、ホームへの二度目のジェスチャーは純正アニメーションへ渡す。
- Android 16 / 17のフォルダ契約を個別に照合。


## 0.15.0 (2026-10-22)

- Expand all four Quick Panel size and thickness controls to 25–200%, retaining 5% steps and existing values.
- Remove the previous visual minimum that prevented lower percentages from taking effect.
- Add an Android 16 design preset with 110% bars and 105% buttons, while retaining native 100% and independent controls.
- Replace the hand-drawn preview with a private embedded SystemUI surface, using native controls and live tile state. Stop its rendering and listeners when the app loses focus or closes.

## 0.14.0 (2026-10-22)

- Add an Android 16 design switch for brightness, volume, dark/light mode and sound mode on the reviewed Android 17 firmware.
- Use native grouped controls through a read-only presentation list; preserve saved layout and use the standard editor.
- Keep four independent size settings across design changes, including enlargement of the old-style round buttons.
- Bound presentation caches and isolate design failures from the existing size controls. Add nine geometry and sizing tests.

## 0.13.0 (2026-10-22)

- Add a dedicated Quick Panel page with a live size preview and four independent controls: brightness and volume bar thickness, dark/light mode button size, and sound-mode button size.
- Keep standard sizes at 100%, restore them on disable, and apply changes on the next panel opening. Separate Android 16 View and Android 17 Compose adapters.
- Count confirmed early crashes rather than startup attempts; keep intentional restarts and unknown exit reasons out of the crash count. Show host crash protection on the new page.
- Add a reproducible signed-release packaging and verification tool.

## 0.12.0 (2026-10-18, experimental)

- Add Recents-only focus notification modes through a shared system_server backend. Require the common Vector `system` scope and One UI Home, with no source-app scopes or hooks.
- Deliver a copied Samsung core-state bundle only to the owned fullscreen app process. Preserve global MultiStar and top-resumed settings, filter subsequent state deliveries, and restore the latest native state on exit.
- Start disarmed after each system process restart; require explicit activation and reject unknown builds, identities, window modes and conflicting visible activities. Bound a lost lease to ten minutes and retry restoration at most three times.
- Add localized Material 3 controls and graphical backend status. Reset the dropdown filter before showing all modes and map choices by value. Distinguish dispatch from a verified target-app callback.
- Verify notify and suppression in portrait/landscape with an unscoped Canvas/AudioTrack fixture, native mode, and held landscape Recents with Home Up OFF. Restore before Home Up’s gesture task-launch request, which can precede animation completion. Verify cancel → source → Home callbacks in portrait with Task Changer ON and landscape with it ON/OFF; repeat the previously failing ON/landscape case. Actual Garupa audio and exceptional recovery paths remain unverified.
- Abort a new lease if restoring its predecessor failed; do not hide a disabled backend or block restoration retries. This exceptional path has not been forced on the device.
- Pass 75 unit tests and release Lint; build a signed, non-debuggable APK.

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
