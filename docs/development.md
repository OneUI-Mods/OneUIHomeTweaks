# 開発者向けガイド

利用者向けの機能説明・導入手順は[README](../README.md)を参照してください。

## ビルド

JDK 21とAndroid SDK 36を用意し、Android Studioでプロジェクトを開きます。開発用チェックアウトは `~/Documents/OneUiLiveColors` に置いています。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APKの出力先は `app/build/outputs/apk/debug/app-debug.apk` です。Gradle Wrapperを同梱しています。独自コードのパッケージ名はドメイン `ryuya-dev.net` をJavaの識別子に合わせた `net.ryuya_dev.oneuilivecolors` です。

配布APKは現在、開発用署名です。GitHub Actionsの一時環境のdebug鍵とは異なります。配布元の異なるAPKを上書き更新することはできません。本格的な継続配布には、保管・管理した署名鍵を継続して使用する必要があります。

## 実装の方針

- 壁紙の標準 `WallpaperColors` と色変更通知を使用し、One UI Homeの既存の文字色決定処理へ補います。
- `WallpaperColors.getColorHints()` のグローバルな書き換えはしません。
- ライブ壁紙かどうかは `WallpaperInfo` で判定し、壁紙アプリのパッケージ名・private設定・描画実装には依存しません。
- Home UpはLauncher内に動的ロードされたクラスを扱います。壁紙アプリやHome Up本体のプロセスには適用しません。
- クラス構造・シグネチャ・実際のDEX呼び出し関係を検証し、互換性を確認できない機能は停止します。難読化された名前だけに依存する探索は避けます。
- 色、壁紙、ページ、設定のイベントを使用し、定期ポーリングは行いません。
- システムパーティション、root実装、壁紙画像、ホーム配置は変更しません。端末再起動も行いません。

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
