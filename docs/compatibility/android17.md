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

## system_server focus契約の追加照合

`ActivityTaskManagerServiceExt.mCoreStateController`、`CoreStateController.getCoreStates(int)`、`ClientLifecycleManager.scheduleTransaction(ClientTransaction):boolean`、`RootWindowContainer.anyTaskForId(int,int,ActivityOptions,boolean)`、`WindowManagerService.updateFocusedWindowLocked(int,boolean)`を17のservicesで照合しました。ClientTransaction・CoreStatesChangeItemのconstructorとフィールド、ActivityRecordの状態通知、WindowProcessControllerのUID/thread/activityフィールドも存在します。実機の完全一致fingerprintを17専用定義へ追加し、discoveryを許可します。実際の変更前に全契約を解決する処理と、利用者が設定するまでdisarmedの動作は維持します。実機でのfocus復元は読み込み後に別途確認します。

## 実機デバッグと設定UI（2026-10-20）

最後にzygoteをソフトリブートし、カーネルのboot IDが変わらないこと、SELinux EnforcingとTailADBの復帰を確認しました。解除後、Barは背景取得・文字ごとの配色・ライブ描画が動き、Home Tweaksはホームの場所別配色・時計・音量・壁紙プレビューがACTIVEになりました。Captureは録画対象一覧を取得でき、アプリ録画の縦開始→横→逆横→縦を1080×2340の固定枠で記録しました。逆横で180度を無視する設定も維持されています。

設定UIは機能ごとにまとまりを作り、M3の面・文字階層・操作の優先度を整理しています。プレビューは合成サンプルで、実際の通知や壁紙を読み取るものではありません。Samsung設定内のUIは純正コンテナを維持します。

## 設定UI・録画の追加確認（2026-10-20）

署名済みrelease版のBar 0.6.4、Capture 0.3.3、Home Tweaks 0.12.2を上書きし、3本の設定画面を実機で表示しました。単独アプリのUIはM3のカード、文字階層、合成プレビューを整理しています。Samsungへ差し込む画面の部品は変更していません。現在のJVM試験はBar 97件、Capture 59件、Home Tweaks 84件で、release Lintも通過しています。

Samsungのウィンドウ録画でBarのテスト背景を選び、同じアプリのMainActivityへ切り替えました。保存された動画は選択したPatternActivityの表示を維持し、切り替え先の設定画面を含みませんでした。対象アプリの内部音は880 Hzの合成音、メディア＋マイクはAACトラックとマイク入力で確認しました。スピーカー・本体メディア音量0の状態では純正の音量係数に従って内部音が無音になるため、録画専用音量100%を一時的に有効にして、メディア＋マイクで内部音も入ることを確認しました。専用音量のON/OFFは元へ戻し、確認用アプリは削除しました。生の音声・動画・端末設定は開発用の非公開証跡にのみ保存しています。

Android 16の実機再試験、Galaxyに存在しないAOSP標準録画画面、Home Tweaksのfocus変更を含む全設定の組み合わせを検証済みとは扱いません。16の定義と基準ソースは保持しています。
