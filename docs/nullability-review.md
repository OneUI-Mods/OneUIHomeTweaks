# Kotlin nullable契約の再照合（2026-10-31）

移行前Javaと比較し、正常なnullや省略入力を検証関数へ渡す前に`!!`で停止する経路を修正した。
グローバルに例外を無視したり、認証・入力検証を取り除いたりする修正ではない。

- layout_append: records未指定をHomeLayoutJournal.appendの既存のIOException検証へ渡す。Launcher/自己UIDの認証は維持する。
- grid_result: extras=nullをGridSaveStore.resultの既存のSecurityException検証へ渡す。LauncherのUID・パッケージ認証、期限・token検証を維持する。配置DBへの保存処理は変更しない。
- Android16/17の専用AOSPエミュレータだけに、com.sec.android.app.launcherというテスト専用のホストを入れ、署名済みrelease APKの実際のContentProviderへBinder通信する。One UIホームの描画や配置の試験とは区別する。
- 共通の独立フィクスチャは[HomePod Bridgeの開発用ソース](https://github.com/OneUI-Mods/HomePodBridge/tree/v0.1.6-experimental/tools/fixtures/null-contracts)に収録する。実機へテスト専用ホストを入れない。

## 検証の範囲

修正前の署名済みrelease APKで、各該当経路のNullPointerExceptionをAndroid16/17の両方で再現した。
RootHalInputは無効なソース名を渡し、音声・ソケット・rootホスト作成前の既存ガードに到達するかを確認する。
独立したproviderテストは、テストプロセスのBinder呼出元情報だけを一時設定し、終了時に復元する。インストールされたアプリや外部IPCの認証を変更しない。
実機での再生・録画・撮影、全機能の組み合わせ、Android16のSamsung実機の動作保証とは別の確認である。
