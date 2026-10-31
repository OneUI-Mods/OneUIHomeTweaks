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
