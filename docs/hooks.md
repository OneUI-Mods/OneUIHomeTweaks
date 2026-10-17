# フック経路と更新への対応

## One UI Homeの色

`GlobalSettingsDataSource`、`SettingsKey`、`WhiteBgColorUpdater`の型・戻り値を確認し、DEXから実際のSystem設定読み取りとContentObserverの型関係を調べる。難読化名`V`等を決め打ちしない。libxposed API 102の`deoptimize(Executable)`が成功する場合だけ、実機でインライン化を確認した関連メソッドを限定してdeoptimizeする。

`Settings.System.getString/getStringForUser`の結果を、`need_dark_font`、user 0、実際の呼び出しスタックがデータソース実装である場合だけ補う。保存値は変更せず、Launcherの既存Flow／文字色処理を使う。`WallpaperColors.getColorHints()`の全体フックはしない。

SamsungのSemキャッシュを書き換えると他の画面へ影響するため、今回の修正は利用側の限定ブリッジを採用した。静止画では結果を補わない。特定のライブ壁紙のパッケージ名を条件にせず、標準WallpaperInfoが示すライブ壁紙と標準色APIを使う。壁紙アプリ自身をフックせず、private設定を読む処理も製品に含めない。

## ホームラベルの場所別自動配色（0.7.0）

`IconViewImpl`が`TextView`を継承し、`getCustomLabelColor(): Integer` / `setCustomLabelColor(Integer): void`が実際の表示色を設定することを実APKで確認した。ホームの実Activityと同じViewツリーを走査し、workspace / Home Up freegrid / hotseatの祖先を持つアイコンだけに適用する。ドロワー・開いたフォルダー内・Dressroom内の仮ホーム・Recentsには適用しない。

実frameworkの`IWindowManager.screenshotWallpaper(): Bitmap`と、WMS→WallpaperControllerの実装を確認した。WMSは既存の`READ_FRAME_BUFFER`権限を要求し、WallpaperControllerは現在可視の壁紙Surfaceだけを`captureLayers`する。Launcherにはこの権限が元から付与済みで、追加の権限付与・root実行・画面全体のキャプチャは行わない。実機の画像と主画面は共に1440×3120だった。画像寸法が一致しない場合にスクロール・クロップを推測せず、機能を適用しない。

TextViewの実Layoutの各行、compound drawableを含むtotal padding、親の変換行列から文字の表示範囲を求める。壁紙を最大高さ1024pxに縮小し、各ラベルの範囲のsRGB相対輝度から黒・白のコントラストを求める。各候補の下位20%側のコントラストを比較し、12%の切替余裕で境界の点滅を抑える。複雑な背景の全画素で可読性を保証するものではない。

標準のlocal wallpaper colors APIも調査した。実Engineの既定実装は64px規模のPixelCopyとページ座標・ページ単位キャッシュを使う。動的なページ別描画の現在位置と細かいラベル範囲を確実に対応させるため、今回は実画面寸法を検証できる壁紙専用キャプチャを採用した。壁紙アプリへの追加フックはない。

ホームのresume/focus、レイアウト・スクロール、標準の壁紙色通知に反応する。イベント後180/400/750/1200/1800/2600msの有限回だけ採取し、スクロール継続中は古い処理を世代番号で破棄する。常設タイマー・バックグラウンドのポーリングはない。通知されない画像切替や2600msを超える遷移は次のイベントまで追従しない場合がある。主画面・縦向き・全画面・非ロック・ホームフォーカス時だけ処理する。

ラベルごとの元のcustom色を記録し、無効化・ホーム以外への再利用・未知の座標・キャプチャエラー時は、モジュールが最後に設定した値だけを元に戻す。他者が後から設定したcustom色は上書きして復旧しない。Bitmapは一時メモリ内だけで処理・recycleし、保存・外部送信しない。ページが画面外に出ただけではcustom色を解除せず、同じページへ戻るときの標準色のフラッシュを避ける。壁紙が一時的に非表示でBitmapがnullになる場合も直前の色を保つ。変更色は、同じ局所画素列が連続2回安定し、色候補が一致してから適用する。

下部のファインダーは`PageIndicatorView.updateFinderButton(IndicatorSearchLayoutBinding)`と`StrokeTextView`の実装を確認した。リソース名`finderTextView`、TextView型、実際のPageIndicatorView祖先を併せて確認し、同じホーム内のその文字だけを対象とする。Home Upの別ローダーで同名のTextViewを使用する場合もView型・ID・祖先で絞る。StrokeTextViewはLayout全体ではなくcompoundPaddingLeftとbaselineで直接描画するため、Paintの実字形範囲を使う。文字と子アイコンは描画せず、検索ボックスの祖先背景Drawableだけを局所Bitmapに描き、壁紙の対象画素へ合成する。読み取りの対象は文字範囲と周囲2dp/1dpだけで、画面全体の平均色を計算しない。

ファインダーの標準色更新を確認した`TextView.setTextColor(int)`だけをLauncher内でフックし、追跡済みのそのViewに限り直前の適用色を保持する。標準からの新しい要求は復旧用に記録する。実際の呼び出し元updateFinderButtonを型検証して限定deoptimizeする。設定解除時は元のColorStateListに戻す。0.7.1から同じfinderContainer直下のImageViewとリソース名searchIconを確認し、虫眼鏡のimageTintも文字と同じ判定色にする。元のtintを記録し、無効化・親の再利用時はモジュールの適用値だけを元へ戻す。背景・画像リソース・tint modeは変更しない。ステータスバーには適用しない。RTLでのファインダーの字形範囲は未検証のため追加配色を行わない。

ステータスバーはユーザーが別モジュールで管理するため、0.7.0から`need_dark_statusbar`の読み取り結果を補完しない。設定UIからも色指定を除いた。保存済みの旧`status`値は移行時に適用せず、OS保存値や他モジュールの設定は変更しない。

## ページ通知

通常画面は`WorkspaceFastRecyclerView.notifyScrollX(int,int,int)`、`onEndPageScrolling()`、`onAttachedToWindow()`。DEXで`GradientBackgroundManager.onScroll`への参照を確認する。

実機ではHome Up 17.5.00.30のフリースタイル画面も有効だった。`PluginContextWrapper`のコンストラクタと`getClassLoader()`で、実際のHome Upプラグイン用ローダーを取得する。プラグインAPKのDEXも確認してから`FreeGridFastRecyclerView`の同じイベントをフックする。Launcher側の通常クラスとプラグイン側ではローダーが異なる。

通常のセルだけを数え、Discover／追加ページ等を除外。`getScrollForPage()`で実際の配置を取得し、RTL・不均一な間隔を含めて正規化する。`setWallpaperOffsetSteps()`と`setWallpaperOffsets()`を1描画フレームに最大1回送る。定期ポーリングと擬似スワイプはしない。

**実際のLauncher ActivityのDecorViewと同じwindow token、ホームにフォーカスがある場合だけ送信する。** `activities.LauncherActivity`はActivityの別名で、実装クラスは`com.sec.android.app.launcher.Launcher`。Wallpaper and styleのSurfaceControlViewHost内でもホームのViewが作られるが、その仮ホームは対象外。

## ドロワー

`IconViewImpl.getCustomLabelColor/setCustomLabelColor(Integer)`と`setItemStyle(ItemStyle)`を確認。ドロワーの祖先Viewを持つアイコンだけにSamsungのカスタム色APIを適用し、解除時は記録した元の値へ戻す。自動では既存動作を維持する。

## アプリ履歴の縦向き固定

実機LauncherのManifestで`com.android.quickstep.RecentsActivity`を確認した。初期の`screenOrientation`は`UNSPECIFIED`（-1）。DEXでは`TaskListViewModel`が`OverviewEventHandler.getCurrentActivity()`を通じて現在のActivityに`setRequestedOrientation(14)`（LOCKED）を要求し、別の回転管理処理がUSER（2）も要求する。このため、横向きアプリから入ると横向きの履歴が保持される。

`RecentsActivity.onCreate(Bundle)`・`onResume()`の前と、Launcher内の`Activity.setRequestedOrientation(int)`に限定したフックを置く。実際のActivityが検証済みRecentsActivityであり、display 0・全画面の場合だけPORTRAIT（1）へ補正する。アプリタスクや通常のホームActivityは対象外。元の要求をActivity単位で記録し、機能・対象・全体スイッチの解除時に元へ戻す。

Activity継承、ライフサイクルの戻り値・引数、向き要求メソッドの宣言元を実行時に検証する。呼び出し元は実APKのDEX参照から探索して限定deoptimizeし、実機では9メソッドだった。難読化された`C7.c`等の名前には依存しない。検証失敗は履歴機能だけを停止する。

## 履歴画面だけホームバーを非表示

実機`NavigationModeSource`はAndroidの`config_navBarInteractionMode`を読み、0=3ボタン、1=2ボタン、2=標準ジェスチャー、3=Samsungジェスチャーに変換する。`NavigationBarVisibilityCheckerImpl`はさらに`navigation_bar_gesture_hint`、NavStarのフラグ、`policy_control`等を参照して標準の表示状態を決めている。

この機能は検証済みRecentsActivityの`WindowInsetsController`だけに`hide(navigationBars())`を要求する。リソース値が2/3、OSのヒント表示が1、主画面・全画面の場合のみ適用する。0/1・不明な値・常時非表示設定では追加の非表示処理をしない。OS設定の書き込みやSystemUIへの新しいフックはない。

実frameworkで確認した`WindowInsetsController.getRequestedVisibleTypes()`と`InsetsController.show/hide(int)`の契約を実行時検証し、変更前のナビバー表示要求とsystem bars behaviorを記録する。ウィンドウ接続前のPendingInsetsControllerから実controllerへ切り替わっても、同じActivityの元の表示要求を引き継ぎ、モジュール自身の非表示要求を元の設定として誤保存しない。標準の後続show/hide要求も、そのRecents用controllerだけで記録する。解除時は元の要求とbehaviorへ戻し、OS側のヒント非表示を再表示しない。statusBarsのビットは変更しない。

この端末ではナビバーを隠すと没入表示の判定によりホームスワイプも停止した。`SystemGestureUseCase.isAllowGestureIgnoringBarVisibility()`を、今回の機能でバーを隠したRecentsActivityがフォーカスを持ち、ロックされておらず、ジェスチャー方式の場合だけ補う。`RecentsAnimationState.canStartWithNavHidden()`等の実装と4つの呼び出し元を検証してdeoptimizeする。バー表示以外のホーム・履歴の禁止条件は標準処理に任せ、通常アプリ・通知画面・ロック画面へは適用しない。操作方式・OS設定の変更はContentObserverとActivityの構成変更で検出し、ポーリングはしない。

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

SDK 36 / SM-S948Q / owner user / 対象メインプロセスに限定。SDK更新、型の不一致、曖昧なDEX証拠、libxposed API 102不在、繰り返す設定読み取り失敗時は対応機能を停止する。Launcher更新に対する構造探索はあるが、将来の互換性を保証しない。Android 17 / One UI 9では新しい実機とバイナリから再調査する。

### 未訪問ページの初回表示

訪問済みページの保持だけでは、未訪問ページのnative白→判定色という初回フラッシュが残る。確認済み`IconViewImpl.onDraw(Canvas)`の直前に、実ホームの同じDecorView・縦画面・画面内・未判定アイコンだけ、文字のcustom色を一時的に透明にする。アイコン画像とレイアウトは維持する。局所画素が安定して色が決まったら120msで文字だけフェードインする。訪問済みの文字は隠さない。

未知の画素や座標を取得し続ける場合は、有限の採取が終了した3秒時点で未判定文字を標準色へ戻す。同じViewを直後に再び隠すループを防ぎ、OFF・破棄・エラー時は表示アニメーションもキャンセルして元のcustom色を戻す。取得画像の寸法不一致が続く間は初回非表示処理を止める。
