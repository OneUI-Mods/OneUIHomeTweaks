# 開発者向けガイド

表示名は0.6.0からOne UI Home Tweaks（旧One UI Live Colors）。更新互換性のためapplication ID、provider authority、署名鍵、ログタグと開発フォルダは継続する。Gradleのプロジェクト名と配布APK名は新名称を使う。

利用者向けの機能説明・導入手順は[README](../README.md)を参照してください。

## ビルド

JDK 21とAndroid SDK 36を用意し、Android Studioでプロジェクトを開きます。開発用チェックアウトは `~/Documents/OneUiLiveColors` に置いています。

```sh
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

Gradle Wrapperを同梱しています。独自コードのパッケージ名はドメイン `ryuya-dev.net` をJavaの識別子に合わせた `net.ryuya_dev.oneuilivecolors` です。

モジュールは`io.github.libxposed:api:102.0.0`をcompile-onlyで使用します。エントリ、固定スコープ、API要件は`app/src/main/resources/META-INF/xposed/`で登録し、legacy API 82のjarや`assets/xposed_init`は使用しません。

## 配布APKの署名

配布にはdebug鍵を使わず、専用のrelease鍵を継続して使います。鍵がない通常のビルド・CIは `app/build/outputs/apk/release/app-release-unsigned.apk` を生成します。これは未署名の検証成果物で、そのままインストールする配布APKではありません。

署名してビルドする場合は、リポジトリの外に次の内容のpropertiesファイルを用意します。

```properties
storeFile=/absolute/path/to/release.jks
storePassword=YOUR_PRIVATE_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_PRIVATE_KEY_PASSWORD
```

```sh
export ONEUI_SIGNING_PROPERTIES=/absolute/path/to/private-signing.properties
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

署名済みAPKは `app/build/outputs/apk/release/app-release.apk` です。`debuggable=false`、コードの難読化は無効です。外部フック・reflectionで使う名前を保持する必要があるため、releaseビルドでも無条件に難読化しません。

署名鍵とパスワードはGitに追加しないでください。現在のローカル配布鍵は `~/.android/keystores/oneui-live-colors-release.jks`、設定は同ディレクトリの `oneui-live-colors-signing.properties` で、それぞれ読み書きを本人だけに制限しています。更新に必要なため、この2ファイルは別途安全にバックアップしてください。GitHubへ鍵をアップロードする自動設定は行っていません。

配布鍵の証明書SHA-256：`7938e1850451b95c45c8f138a768c9da7c5d9db7fb61a883ac8dc724077ddb03`。

初期debug版とrelease版は署名が異なるため、debug版を削除してから移行する必要があります。以後は同じrelease鍵なら通常の上書き更新が可能です。

## 実装の方針

- 壁紙の標準 `WallpaperColors` と色変更通知を使用し、One UI Homeの既存の文字色決定処理へ補います。
- `WallpaperColors.getColorHints()` のグローバルな書き換えはしません。
- ライブ壁紙かどうかは `WallpaperInfo` で判定し、壁紙アプリのパッケージ名・private設定・描画実装には依存しません。
- Home UpはLauncher内に動的ロードされたクラスを扱います。壁紙アプリやHome Up本体のプロセスには適用しません。
- クラス構造・シグネチャ・実際のDEX呼び出し関係を検証し、互換性を確認できない機能は停止します。難読化された名前だけに依存する探索は避けます。
- 色、壁紙、ページ、設定のイベントを使用し、定期ポーリングは行いません。
- システムパーティション、root実装、壁紙画像は変更しません。ホーム配置の保存は利用者が専用操作で確認した場合だけ行います。端末再起動は行いません。

## 調査・検証資料

- [OS更新前の実機情報・APKハッシュ・標準／Sem色情報の経路](device-baseline-2026-10-15.md)
- [対象クラス・フックの根拠・互換性方針](hooks.md)
- [実機確認記録・検証手順・切り戻し](testing.md)
- [変更履歴](../CHANGELOG.md)

実機で未確認の項目は検証資料に残し、フック登録やログ出力の確認と、画面上の動作確認を区別します。Android 17 / One UI 9は再解析まで無効です。

## ログと調査ツール

```sh
adb logcat -s OneUiLiveColors:I '*:S'
```

`tools/WallpaperProbe.java` は端末の標準／Samsung壁紙APIを読み取り確認する調査用ソース、`tools/DexReferences.java` は取得済みAPK内の実際の参照を調べるデスクトップ用ソースです。

端末のAPK、逆コンパイルしたソース、壁紙画像、個人ログ、端末識別子はリポジトリやリリースへ含めません。ローカルの調査成果物は `.gitignore` の対象です。

## 依存ライブラリとライセンス

`third_party/components.tsv` と実際の依存関係に差分がある場合はビルドを停止します。依存の追加・更新時は、推移的依存も含めて一覧・ライセンス原文・通知を確認してください。ビルド時に本アプリのMIT Licenseと依存のライセンス表示をアプリ用assetsに生成します。

## 公開前の確認

単体テスト・Lint・APKビルド・CIの結果に加え、変更したフックの実機動作を確認します。APKのSHA-256、署名の扱い、確認した環境、未確認の項目をリリースノートに記載します。Samsungの非公開実装を配布物へ含めないでください。

## 場所別配色

`HomeLabelBridge`がホームラベルの実位置と現在の壁紙Surfaceを対応付け、`LocalContrast`で領域別の黒・白を選びます。詳細と制約は[フック経路](hooks.md#ホームラベルの場所別自動配色070)と[検証手順](testing.md)を参照してください。場所別配色は設定値4で、従来の0〜3の意味は維持しています。ステータスバー色の上書きは0.7.0から無効です。

## DIY Homeのグリッド整列

対象メソッド、座標の扱い、編集時の吸着と実機検証の範囲は[DIY Homeの技術記録](diy-home-grid.md)を参照してください。SamsungのAPK・逆コンパイル結果・ホームDB・壁紙画像は私用の調査資料で、公開ソースや配布APKには含めません。

- [履歴の連続アニメーション・ライブ描画の技術記録](recents-motion.md)


## 履歴のライブ描画

0.11.0で縦向き固定と独自の回転・遷移補正を削除しました。方向要求のフック、共通viewportの再描画・insets補完・spring・handoff barrierは登録しません。縦・横とも標準遷移の終了要求後にライブ描画を開始し、実測したウィンドウの寸法・回転・ネイティブplayerのbasisを検証して配置します。画面の向きを要求・変更しません。

`-PrecentsLiveTrace=true`は残したライブ描画の調査用フラグです。通常はfalseで、詳細なカード情報を出しません。旧`recentsMotionTrace`フラグと遷移の測定器は削除しました。公開APKは追加のGradleプロパティなしでビルドしてください。

残した機能の実装は[継続描画の技術記録](recents-live.md)を参照してください。過去の対象メソッド、試作、実機記録は[履歴の技術記録](recents-motion.md)に残しています。0.10系の調査は現行機能の説明ではありません。OS・Launcher・Home Up更新時は残した機能を再検証してください。

## 履歴のフォーカス通知（0.12.0検証版）

[共通バックエンドの設計と検証記録](recents-focus.md)を参照。`system`スコープを追加し、`onSystemServerStarting`で読み込む。通常のホストとは異なり起動時は無効で、手動有効化が必要。対象アプリを追加する方式は撤回した。ゲームの処理や音声の直接フックは行わない。
