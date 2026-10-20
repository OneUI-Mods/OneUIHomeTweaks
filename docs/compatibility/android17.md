# Android 17の互換性記録

- 対象候補: Android 17 / API 37、SM-S948Q、ファームウェアS948QOPU1BZI9。
- 状態: ROMと実機APKの一致を確認し、BZI9専用のフック入場条件を有効化。機能ごとの実機確認中です。
- `Android17Hooks`は独立したプロファイルです。実装と動作を確認するまではフックを許可しません。16の許可条件を広げて17へ流用しません。
- 今後はAPK／frameworkのハッシュ、クラス・メソッド・引数・フィールド・呼び出し関係、実機で確認した項目をこの記録へ追記します。
- ビルドの成功、フック登録の成功、画面上の機能確認は別々に記録します。

## 分離準備の検証

Android 16の許可条件の維持、17から16へフォールバックしないこと、未対応SDKを暗黙に有効化しないことを確認しました。既存分を含むJVMテスト79件、release Lint、releaseビルドが通過しています。この段階のAPKは端末へインストールしていません。17の機能確認済みという意味ではありません。

## BZI9 ROMとの照合（2026-10-20）

One UIホーム18.0.07.53 / 1800705053、SystemUI、DressRoom、framework、servicesを抽出し、16のAPKと照合しました。SHA-256と版は[android17-rom.json](android17-rom.json)へ保存しています。systemパーティションのfingerprintを実機の`Build.FINGERPRINT`として許可条件に使用していません。

| 処理 | Android 16 | Android 17で確認した変更 |
| --- | --- | --- |
| 履歴カード | `com.honeyspace.ui.common.taskScene.TaskSceneView/Data` | `com.honeyspace.common.taskscene.TaskSceneView/Data`へ移動 |
| 最前面タスク取得 | `TopTaskUseCase.getTopTask()` | `runningTaskForDisplayId(int)`へ変更。managerのdisplayIdを渡す |
| 終了要求 | `AnimationSession.requestFinishRecentAnimation()` | `requestFinishRecentAnimation(boolean toHome)`へ変更 |
| 時計のビルド限定 | Android 16のAZHL表示 | 17のBZI9表示を別定義へ保存 |

17の終了要求の本体を確認しました。`willGoToRecents && dontFinishByCommand`なら終了せず、それ以外では`toHome`に応じて`willFinishToHome()`を呼んでから終了します。フックは元のboolean引数を変更せず、既存の前後処理だけを登録する準備にしています。

16の引数なし呼び出しとクラス名は維持しています。`BackgroundBlurDrawable.mBlurRadius`は16/17両方に存在するため、その経路を推測で置き換えていません。JVMテスト83件、release Lint、releaseビルドが通過しました。17のフック許可およびsystem_serverのfocus経路は引き続き無効です。

## 残る確認

- Home Up側のクラス・ステッカー・スクロール終端。17のホーム本体にないクラスをHome Up全体から削除されたものとは断定しないこと。
- 履歴のlive surface、focus、文字入力、戻る・ホーム・タスク切り替え、動画・ぼかしの復元。
- ランチャーの実機版、実機fingerprint、Vector、クラッシュ保護。
- 壁紙・時計・色と、設定OFF時の復元。

TailADBが接続拒否のため、実機確認・インストールは未実施です。TailADBの更新は利用者の指示で保留し、端末・SystemUI・ホームを再起動していません。

## 実機照合と読み込み準備（2026-10-20）

稼働中のビルドとAPKをTailADB経由で読み取り、SystemUI・One UIホーム・SamsungキャプチャがROMのSHA-256と一致することを確認しました。実機fingerprintはJSONの`runtime_fingerprint`に記録しています。更新保留は利用者が解除しています。

SM-S948Q / m3q / Samsung / SDK 37 / BZI9完全一致の入場条件を17用定義へ追加しました。16の条件は変更していません。個々の機能は引き続き必要なクラス・引数・フィールドを実行時に照合します。これは画面上の全機能を検証済みにする変更ではありません。

端末・SystemUI・ホームを再起動する前に、Samsungキャプチャなど単独プロセスで検証できるものを確認します。必要なソフトリブートは全体の最後にまとめます。

Home Up 18.0.00.36（code 1800005036、SHA-256 `8aa00d2cdcba89068a75f8571a54dfe60d5e255392b254b9675ab6bf903ac20e`）を実機から取得しました。`FrameControlStickerView`、`StickerView`、`FreeGridFastRecyclerView.notifyScrollX(int,int,int)`・`onEndPageScrolling()`はプラグイン側に存在します。ホーム本体のWorkspaceにも両スクロールメソッドは存在し、削除されていません。system_serverのfocus経路は別の確認が必要なため、17では引き続き無効です。
