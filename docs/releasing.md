# 配布手順

ユーザーへ渡すAPKとGitHub Release添付APKには、継続使用中の配布鍵で署名したrelease variantだけを使います。debug版、未署名APK、検証専用Activityを配布しません。

JDK 21、Android SDK / Build Tools 37.0.0を使用します。鍵はリポジトリ外へ保管し、`ONEUI_SIGNING_PROPERTIES`でその設定ファイルを渡します。秘密の値をコマンド引数・ログ・Gitへ書かないでください。設定項目は[開発手順](development.md)を参照してください。

1. 意図した変更・公開用リリース文・開発用検証記録をコミットし、作業ツリーをcleanにします。
2. `python3 tools/package_release.py --source-ref HEAD`を実行します。releaseビルド・JVM試験・Lintに続き、variant、配布証明書、アライメント、API 102、スコープ、検証用Activityの混入を検査します。
3. `artifacts/release/<version>/`のAPK、ソースZIP、SHA256SUMS.txtを添付します。ソースZIPのコミットとタグを一致させます。既存タグを移動しません。
4. タイトル・説明は外部利用者向けとし、機能、対応環境、更新操作、制限を記載します。端末固有の設定値、内部クラス名、試験手順やCI報告を掲載しません。

CIでは`--check-release-apk`で構造を検査できます。これは署名検証を省くため配布可能の判定ではありません。
