# SM-S948Q 実機の基準記録（2026-10-15〜16 JST）

OS更新後にこの環境を再取得できなくなるため、検証対象と根拠を固定する。端末のシリアル番号、アプリ一覧、画像、APK、逆コンパイルしたSamsungコードは公開しない。

## 確認した環境

| 項目 | 実機から取得した値 |
|---|---|
| モデル | Samsung SM-S948Q / m3qjpnw |
| Android / SDK | 16 / 36 |
| One UIプロパティ | `ro.build.version.oneui=80500`（8.5系を示す値） |
| ビルド表示 | `BP4A.251205.006.S948QOPS1AZHL` |
| fingerprint | `samsung/m3qjpnw/m3q:16/BP4A.251205.006/S948QOPS1AZHL_SJP1AZHL:user/release-keys` |
| One UI Home | `com.sec.android.app.launcher` 17.5.03.27 / code 1750305027 |
| Home Up | `com.samsung.android.app.homestar` 17.5.00.30 / code 1750005030 |
| DressRoom | 2.9.50.90 / code 295090000 |
| Sound Assistant | `com.samsung.android.soundassistant` 8.5.00.2 / code 850000002 |
| SystemUI | `com.android.systemui` 16 / code 36 |
| MultiPicture | `jp.kamesan.android.multiple_picture_wallpaper_2` 5.17 / code 75 / targetSdk 36 |
| Vector | 2.2 / build 3080 / runtime API 102（0.4.1まではlegacy互換、0.5.0からmodern API 102） |
| 既存Zygisk実装 | OnyxZygisk v1.09 / code 399 |
| root確認 | `su -c id` が uid=0、SELinux context `u:r:ksu:s0` |
| ユーザー / display | user 0 / display 0 |
| 解像度 | 1440 × 3120 |

root化方法・ブートローダーの状態はユーザー申告。root取得手法の変更、パーティション書き換え、端末再起動は実施していない。

## 解析対象のSHA-256

| ファイル | SHA-256 |
|---|---|
| One UI Home APK | `e8f0ac6818d066549720ebbeea6ac6c13e04e4ee93a26f5ea35589b6dc0bb291` |
| framework.jar | `5953e27bfe048411154e5c2de6893adca8606e8ca7c3e8910b7cee62cebe8308` |
| services.jar | `c1100fed60fdbb7541004ae8e46a6c4d642d9d605c904762cf854e4809da10ed` |
| Sound Assistant APK | `b1c36809be7d06aab01ae53b630d8fa05155160cc2e289d93d3fc93de31f88f8` |
| SystemUI APK | `d111bca9ee41ea93d08d6d8e258988a944a477bd14154deae53f3baf5d97c3c8` |

| Home Up APK | `8f126bd6016e4de03b127aecd9c8ec0dd492267ac033d38efadf0ed45231e232` |
| DressRoom APK | `ce2362ff95d1ca6cde9f3a3d60df28dc1749ab537e2b6c295d3cc40c3c0ede8d` |

## 壁紙レコードを混同しないための区別

- ホーム: user 0、display 0、id=10、`mWhich=5`、mode=4、MultiPictureの`MultiplePictureWallpaper`。
- ロック: user 0、display 0、id=11、`mWhich=6`、ImageWallpaper。LayeredWallpaperServiceは取得時のバックアップ履歴にも存在し、現在のホームを示さない。
- DeX: Homeレコードの`mWhich=11`に対してSemデータは`which=9`という差が実際に存在。表示された値を勝手に同一視しない。
- sub display: `which=17`の空／未使用レコードも存在する。
- `com.samsung.android.wallpaper.utils.WhichChecker.getType(int)` は `which & 3`、`getMode(int)` は `which & 60`。実機frameworkのコードと実行時APIの存在を確認した。`isPhone()`はmodeのbit4を確認する。この実装で5はsystem/homeの1とphone modeの4を合わせたもの。

## 取得した色情報

標準API `getWallpaperColors(FLAG_SYSTEM)`:

```
primary=ff000000, hints=1, supportsDarkText=true, supportsDarkTheme=false
```

Samsung API `semGetWallpaperColors(5)`:

```
version=22, which=5
adaptive dim=0.09105117 / ff000000, darkmode dim=0.15
SeedColors=[-16777216]
STATUSBAR fontColor=0
BODY      fontColor=0
NAVIBAR   fontColor=0
```

`SemWallpaperColors.FONT_COLOR_WHITE=0`、BLACK=1、GRAY=2と、HOMESCREEN_STATUSBAR=32、BODY=64、NAVIBAR=128を実機reflectionで確認。`need_dark_font`と`need_dark_statusbar`の保存値は両方0だった。

黒いSeedColorは文字色指定ではない。標準hint=1も「現在の画像が明るい」ことを単独では証明しない。MultiPictureのユーザー設定によって強制される可能性がある。

## 実機バイナリで追った経路

1. MultiPictureのEngine実体は`E1.P0`。`onComputeColors()`、`onOffsetsChanged(float,float,float,float,int,int)`、設定変更時の`notifyColorsChanged()`を確認した。難読化名はこのAPKだけの参照用で、製品フックに固定しない。
2. このSamsung frameworkの`WallpaperService.Engine.onComputeColors()`のデフォルト実装はnullを返す。MultiPictureはsuperを呼び、設定次第でprimary色とhintを生成する。画像を自動解析して返すとは限らない。
3. `WallpaperManagerService.WallpaperConnection.onWallpaperColorsChanged(WallpaperColors,int)`は標準`primaryColors`を更新して標準通知を送る。SamsungのSemデータを同時に更新する処理はこのメソッドにはない。
4. `WallpaperManagerService.SemCallback.calcSemWallpaperColors(int,int)`はSamsung提供者用サムネイルやライブ壁紙の`WallpaperInfo.loadThumbnail()`を使う分岐を持つ。MultiPictureの`loadThumbnail()`を実際に読むと、現在の描画ではなく黒背景の画像アイコンだった。設定画面のキャッシュプレビューとライブ描画にもユーザー提供画像で差がある。ただし、プレビュー不一致とSem解析入力を完全に同じ原因と断定しない。
5. `notifySemColorListeners(int,WallpaperData)`がSemの領域32/64/128を`LegibilityColor`のメッセージ1015へ渡す。
6. `LegibilityColor`のHandlerが`Settings.System`の`need_dark_statusbar`、`need_dark_font`、`need_dark_navigationbar`を更新する。
7. Launcherの`GlobalSettingsDataSource`実装はこのAPKでは`com.honeyspace.core.repository.V`。`a(SettingsKey.Type,String)`がSystem/Secure/Globalの文字列を読み、関連クラスSがContentObserverを登録、Qが再読み取りする。
8. `WhiteBgColorUpdater`実装はこのAPKでは`l3.d`。`need_dark_font`を`getDarkFont()`のStateFlowへ流し、`WhiteBgColorChanger`実装`l3.b`がSamsung標準リソースの文字色・影を適用する。
9. Launcherの`changeStatusBarColor(View,boolean,boolean)`がWindowInsetsのappearance bit8等を変更し、SystemUIの`LightBarControllerImpl.onStatusBarAppearanceChanged(...)`が受ける。ホームだけの指定をLauncherから伝えられるためSystemUIへのフックを必要としない。
10. SystemUIの音量UIはテーマリソースや`BlurEffect`の実時間／キャプチャぼかしを使う。テーマの代表色と領域別文字色は別経路。実際の表示ウィンドウはSound Assistantの`VolumeStarStandardWindow`で、独自エフェクトバーの`barDarkUI`も配色を持つ。音量UIが`need_dark_font`を直接読む証拠は確認できていない。

逆コンパイラは制御フローを誤復元することがある。特に`calcSemWallpaperColors`のbitmap null判定など、Javaの表示だけで異常実装と断定しない。フックはそこには入れない。

## ページ通知の根拠（解析中の追記）

- Launcherの`com.sec.android.app.launcher.Launcher.onAttachedToWindow()`に`setWallpaperOffsets()`が存在するが、渡す値はSettingsの`wallpaper_x_offset` / `wallpaper_y_offset`（default 0.5）だった。
- このLauncher APK全体のJADX出力では、`setWallpaperOffsetSteps()`呼び出しは見つからず、ページ移動に合わせる`setWallpaperOffsets()`呼び出しも見つからなかった。
- 実際のページスクロールは`WorkspaceFastRecyclerView.notifyScrollX(int scrollX,int minScrollX,int maxScrollX)`。`FastRecyclerView`が移動・端の処理で通知し、Samsungのグラデーションやウィジェットへ渡している。
- MultiPictureの標準`onOffsetsChanged()`はxOffsetとxStepを受け取り、ページ通知方式へ移るロジックを持つ。Launcher側から標準オフセットを補う方針。

## 実行確認と限界

- 初回実装はARTインライン化のため通知登録の捕捉を確認できず、表示も未変更だった。
- DEXの型関係で設定データソースと関連クラスを探し、当初はVectorのlegacy互換`deoptimizeMethod(Member)`、0.5.0ではlibxposed API 102の`deoptimize(Executable)`で関連4クラス・12メソッドだけをdeoptimizeした。どちらでも`OBSERVE`2件、`READ need_dark_font 0 -> 1`、`DISPATCH local observers=2`を確認した。
- モードの保存変更がLauncherのプロセス再起動なしでSTATE/READ/通知を更新することもログで確認した。
- 上記補完後もシステム設定の保存値は0のまま。修正はLauncherプロセス内だけ。
- 2026-10-17に0.5.0 release版を同じ署名で上書きし、VectorのmodernローダーからAPI 102としてLauncher、SystemUI、DressRoomへ読み込まれることを確認した。色・ページ・ドロワー、標準音量・VolumeStar、現在ホーム壁紙のプレビューがそれぞれ初期化され、fatal crashがないことをログで確認した。
- 新しいLauncherバージョン、Android 17 / One UI 9の実機検証はしていない。Android 17以降では意図的に無効。
- 標準色通知が来ない画像変更や、壁紙が不正確なhintを返す場合に、画面の明暗を保証する機能ではない。Semキャッシュ自体は修復しない。プレビューは後続の限定フックで実描画のミラーに変更した（[追加経路](hooks.md)）。

## 外部の一次資料

- [Android Engine API](https://developer.android.com/reference/android/service/wallpaper/WallpaperService.Engine)
- [WallpaperColors](https://developer.android.com/reference/android/app/WallpaperColors)
- [WallpaperManager](https://developer.android.com/reference/android/app/WallpaperManager)
- [Vector](https://github.com/JingMatrix/Vector)
- [libxposed API 102 deoptimization](https://github.com/JingMatrix/libxposed-api/blob/master/api/src/main/java/io/github/libxposed/api/XposedInterface.java)

外部に公開されている旧Samsung frameworkの解析例は初期調査の参考にしたが、実機確認をそれで代用していない。

## 追記：検証途中の設定変化

MultiPictureの`PREF_SYSTEM_PANEL_COLOR_ENABLED=false`の状態では標準色がnullになった。最初に取得したhint=1が常に出るわけではない。自動モードはnullで標準動作へ戻る。

Home Upのフリースタイルは通常Workspaceとは別ローダー・別Viewを使用する。4ページの実ホームと3ページ設定の壁紙の不一致を確認した。さらに、設定の仮ホームからの位置通知が実ホームへ干渉するため、Activityの実ウィンドウtokenで送信元を限定した。
