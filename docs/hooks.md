# フック経路と更新への対応

## One UI Homeの色

`GlobalSettingsDataSource`、`SettingsKey`、`WhiteBgColorUpdater`の型・戻り値を確認し、DEXから実際のSystem設定読み取りとContentObserverの型関係を調べる。難読化名`V`等を決め打ちしない。Vectorの`deoptimizeMethod(Member)`が利用できる場合だけ、実機でインライン化を確認した関連メソッドを限定してdeoptimizeする。

`Settings.System.getString/getStringForUser`の結果を、`need_dark_font`と`need_dark_statusbar`、user 0、実際の呼び出しスタックがデータソース実装である場合だけ補う。保存値は変更せず、Launcherの既存Flow／文字色／ステータスバー処理を使う。`WallpaperColors.getColorHints()`の全体フックはしない。

SamsungのSemキャッシュを書き換えると他の画面へ影響するため、今回の修正は利用側の限定ブリッジを採用した。静止画では結果を補わない。特定のライブ壁紙のパッケージ名を条件にせず、標準WallpaperInfoが示すライブ壁紙と標準色APIを使う。壁紙アプリ自身をフックせず、private設定を読む処理も製品に含めない。

## ページ通知

通常画面は`WorkspaceFastRecyclerView.notifyScrollX(int,int,int)`、`onEndPageScrolling()`、`onAttachedToWindow()`。DEXで`GradientBackgroundManager.onScroll`への参照を確認する。

実機ではHome Up 17.5.00.30のフリースタイル画面も有効だった。`PluginContextWrapper`のコンストラクタと`getClassLoader()`で、実際のHome Upプラグイン用ローダーを取得する。プラグインAPKのDEXも確認してから`FreeGridFastRecyclerView`の同じイベントをフックする。Launcher側の通常クラスとプラグイン側ではローダーが異なる。

通常のセルだけを数え、Discover／追加ページ等を除外。`getScrollForPage()`で実際の配置を取得し、RTL・不均一な間隔を含めて正規化する。`setWallpaperOffsetSteps()`と`setWallpaperOffsets()`を1描画フレームに最大1回送る。定期ポーリングと擬似スワイプはしない。

**実際のLauncher ActivityのDecorViewと同じwindow token、ホームにフォーカスがある場合だけ送信する。** `activities.LauncherActivity`はActivityの別名で、実装クラスは`com.sec.android.app.launcher.Launcher`。Wallpaper and styleのSurfaceControlViewHost内でもホームのViewが作られるが、その仮ホームは対象外。

## ドロワー

`IconViewImpl.getCustomLabelColor/setCustomLabelColor(Integer)`と`setItemStyle(ItemStyle)`を確認。ドロワーの祖先Viewを持つアイコンだけにSamsungのカスタム色APIを適用し、解除時は記録した元の値へ戻す。自動では既存動作を維持する。

## 音量UI

実機の`VolumeDependency.get(Class)`、`VolumePanelImpl.recreateVolumePanelForNewConfig()`、`view.standard.VolumePanelWindow`のDialog型を確認。標準音量ダイアログ構築中のContext取得だけを対象に、局所的なnight mode構成を渡す。設定が変わったときはSamsung自身の再構築メソッドを使う。

標準パネルには、実際のDEX呼び出し元を確認した`BlurEffect.setRealTimeBlur(View,int,float,int)`／`setCapturedBlur(ImageView,int,Supplier)`のプリセットと、`ColorUtils.getSingleColorStateList(int,Context)`の音量用前景色を補完する。frameworkで存在を確認した`SemBlurInfo.BLUR_UI_MEDIUM_ULTRA_THIN_LIGHT`（106）／DARK（121）を使い、画面0の標準／展開音量View配下だけに限定する。前景のalpha、警告色などは保持する。呼び出し側はDEX参照から限定してdeoptimize（この実機で11メソッド／コンストラクタ）。

実機で表示されていたのはSound Assistant 8.5.00.2の`VolumeStarStandardWindow`だった。標準パネルのContext差し替えだけでは、この追加パネルへ反映されない。`SPluginInstanceManager.PluginHandler.handleLoadPlugin(ComponentName)`の実DEXにある`PluginContextWrapper(Context,ClassLoader)`の呼び出しを確認し、その呼び出し元をdeoptimizeしてプラグインのローダーを取得する。Sound Assistant自身のプロセスはスコープへ追加しない。

プラグインの`VolumeStarImpl`が所有する配色ラッパーを、DEX内の`ColorThemeWrapper`タグ、戻り値int・引数enum、6つの配色キーとリソースの存在から一意に検証する。この実機の難読化名は`f6.i`だがコードには固定しない。明暗指定時だけプラグイン自身のnight別色リソースを解決し、警告色等を保持する。`BarColorModel(progressColor=`のDEXタグとParcelable／constructor(int,boolean)で追加エフェクトバーのモデルも検証し、SystemUI内で生成されるモデルの明暗指定を補う。保存設定は変更しない。

ぼかしは`SemBlurInfo.Builder.setColorCurvePreset`への実参照を持ち、Viewを引数に取るクラスを探索する。この実機は`f6.f`と`f6.c0`。画面0でその処理を実行している同一スレッドの間だけプリセットを補う。設定変更は実装を確認した`VolumeStarImpl.onConfigurationChanged()`で再構築する。OFF時は元の色情報・モデルを再取得する。関連する49呼び出し元を実DEXから限定してdeoptimizeした。新しい版で探索が曖昧・型が異なる場合は追加パネル対応を停止し、状態にエラーを報告する。

SystemUI全体のResources、通知、クイック設定、他アプリのテーマは変更しない。Samsungの特殊ぼかし・テーマ・折りたたみ画面等すべての組み合わせを検証したものではない。

## Wallpaper and style

実機DressRoomの`HomePreviewSurfaceView`タグとSurfaceView／SurfaceHolder.Callback型から対象を一意に特定する。現在版は`b2.j`だが固定フックしない。nativeのアイコンプレビューはSurfacePackageでそのまま使う。

実機frameworkの`IWindowManager.mirrorWallpaperSurface(displayId)`とWMS実装を確認し、現在のホーム壁紙Surfaceをプレビューの描画面の子としてミラーする。アイコンの子Surfaceはその上へ置く。表示中は対象Activityに`FLAG_SHOW_WALLPAPER`を追加して実エンジンを可視に保ち、解除時に元のフラグへ戻す。ロック中・display 0以外は処理しない。

初期試作ではDressRoomの`r4.a`を介して別のプレビューエンジンを作ったが、1ページ目への固定とエンジン終了時の影響があったため廃止した。最終コードはプレビュー用サービスをbindしない。遷移中のちらつきを避けるため、ミラーは`onActivityPaused`ではなく`onActivityStopped`で解除する。

## 安全に停止する条件

SDK 36 / SM-S948Q / owner user / 対象メインプロセスに限定。SDK更新、型の不一致、曖昧なDEX証拠、Vector API不在、繰り返す設定読み取り失敗時は対応機能を停止する。Launcher更新に対する構造探索はあるが、将来の互換性を保証しない。Android 17 / One UI 9では新しい実機とバイナリから再調査する。
