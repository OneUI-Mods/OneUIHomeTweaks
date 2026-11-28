# ホームラベルの未判定色（0.19.5）

実機の設定は有効かつLOCALだったが、Launcherの状態は
`localLabels=error:NullPointerException`。収集時の`prior.applied!!`が原因だった。
Savedは描画時に作られ、配色前・取得タイムアウト後はapplied=nullとなる。
nullはLocalContrastへ渡す正常値であり、Java版もそのまま渡していた。
nullableのままTargetへ引き継ぐよう復元した。他の色選択・復元経路のnullable契約も照合した。

## 独立したAndroid実行テスト

`tools/fixtures/home-label/`は配布APKへ入れない、ウィンドウを作らないapp_process用の
Kotlinフィクスチャ。Android SDKのandroid.jarでコンパイルし、Kotlin runtimeを含むJARを
d8でDEXへ変換する。端末のAndroid runtime環境で次を実行する。

```sh
CLASSPATH=/data/local/tmp/home-label-probe.dex app_process / \
  dev.ryuya.homefixture.HomeLabelCollectionProbe /data/local/tmp/home-release.apk
```

フィクスチャはPathClassLoaderで渡されたrelease APKのHomeLabelBridgeを読み、
実際のTextViewのレイアウト、ホーム祖先判定、remember、collectを実行する。
未追跡・追跡済み未判定・透明な初期待機・黒・白・タイムアウト後を区別して検証する。
Android 17の独立プロセスで必要な共有メモリとフォント初期化もフィクスチャ側で行う。

修正前0.19.4では未追跡ラベルの収集は成功し、remember後の収集で
HomeLabelBridge.kt:460のNullPointerExceptionを再現した。
これはLauncher自身をクラッシュさせず、独立プロセスだけで確認した結果である。
実機上の通常ホーム動作と、Android 16の実機確認とは別に扱う。

## 修正後の検証（2026-10-31）

- 署名済みrelease 0.19.5/build35を同じフィクスチャへ渡し、4項目すべて成功した。
- JVM試験141件、失敗・エラー0。release Lint、継続署名、アライメント、API102、
  検証用Activity非混入の検査が成功した。
- 実機を上書き更新し、One UIホームだけを再起動した。新しいLauncherの
  `MODULE_LOADED build=35`と`localLabels=active black=27 white=0`を確認した。
  ホームのスクリーンショットでも黒いラベルの表示を確認した。現在の壁紙は明るいので、
  暗い壁紙への設定変更は行っていない。白色保持は独立フィクスチャとLocalContrastの
  JVM試験で確認し、実際の壁紙の明暗切り替えは今回の実機検証には含めない。
- 設定XMLの前後差分は0。OSのboot_id、system_server、SystemUIのPIDも不変だった。
  配置DBへの保存、音声再生、OS/ソフトリブート、SystemUI再起動は行っていない。
- Android16向けの分岐は変更していない。今回の実機確認はAndroid17のみ。

## 0.21.262の待機時キャプチャ

ホームのレイアウト通知で、ラベルの描画領域・表示対象・現在の色を確認します。前回のキャプチャと同じ条件なら壁紙の再取得を省きます。壁紙・スクロール・フォーカスなどの通知では従来の再取得経路を使います。Finderの背景は描画領域が同じでも変わり得るため、Finderが表示されるときはレイアウト通知による再取得を維持します。

Android 17のGalaxyで、待機中の約19秒のトレースを変更前後に2回ずつ取得しました。壁紙キャプチャは変更前の41回・34回から、変更後は2回とも0回になりました。設定画面からホームに戻る操作ではキャプチャと27件のラベルの色適用を確認しました。LauncherとシステムのPIDを維持して上書き更新しました。常駐メモリ、Finder表示中、壁紙切り替え操作の実測は今回の範囲に含めていません。
