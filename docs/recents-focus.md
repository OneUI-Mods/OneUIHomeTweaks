# 履歴の継続描画とフォーカス通知（0.12.0実験機能）

## 2026-10-18の確認

Galaxy SM-S948Q / Android 16 / One UI 8.5、Launcher 17.5.03.27、Home Up 17.5.00.30、モジュール0.11.0。比較先はPixel 9 / Android 17。ガルパ10.2.0の音声がGalaxyの履歴では残るという報告から調査した。Pixel側で音が消えることは利用者の報告であり、以下のフォーカス実測とは区別する。

同じ検証Activityで、両方とも履歴中にCanvasのフレームが進むことを確認した。Pixelでは`Activity.onWindowFocusChanged(false)`が届く。Galaxyでは履歴中にこのコールバックが届かず、ホームへ戻ってActivityがpauseされる時点まで遅れる。一方、GalaxyでもViewRootImplのIME関連ログには先に`hasWindowFocus=false`が現れる。入力のフォーカス喪失そのものが発生していない、という説明は正確ではない。

実機の`dumpsys activity`で、次のSamsung実行時状態を確認した。

```text
MW_MULTISTAR_STAY_FOCUS_ACTIVITY_DYNAMIC_ENABLED = true
stay_focus=true, stay_top_resumed=true
```

端末内framework.jarの`com.android.internal.policy.DecorView.onWindowFocusChanged(boolean)`には、`MultiWindowCoreState.MW_MULTISTAR_STAY_FOCUS_ACTIVITY_DYNAMIC_ENABLED`が有効で、通常Activityのウィンドウである場合、`ActivityThread.mayStayActivityFocus(token)`がtrueの間はコールバックのfalseを抑制する実装が存在する。`mayStayActivityFocus`は対象ActivityClientRecordが存在し、pausedでもstoppedでもなければtrueを返す。

継続描画で最後のタスクがRESUMEDのまま残ると、このMultiStarの保持条件も残る。通常のホーム遷移などでpauseされると、`DecorView.releaseActivityFocusIfNeeded()`で保持したフォーカスが解放される。Pixelとの比較、実機の有効状態、実際のフレームワーク分岐が一致する。

ゲームがコールバックに応じて何を止めるかはゲーム側の処理による。`dumpsys audio`で音声トラックがstartedであることだけから、実際に音が聞こえる、あるいは消音されたと判定してはいけない。アプリ内で無音サンプルを出す場合もある。今回、PCMや音圧の測定は行っていない。

## 入力経路の検証と取り消し

所有する履歴が開いた後だけLauncherの`InputConsumerController.unregisterInputConsumer()`を呼び、終了時に元のcontrollerを再登録する検証版を実機で試した。ホームへの復帰と所有Surfaceの解放は確認したが、Activityへのフォーカス喪失通知の遅延は改善しなかった。この変更は破棄し、端末も配布済み0.11.0へ戻す。これは配布機能ではない。

## 追加する設定の扱い

利用者の希望は「フォーカス喪失を通知する／通知しない」を**履歴画面の間だけ**選択できること。履歴以外では既存のMultiStar設定を維持する。加えて、対象のゲームなどを一つずつVectorのスコープに追加する操作は不要にする。0.11.0には未提供。0.12.0の実験機能として共通バックエンドを実装した。実機検証結果は末尾に記載する。

Samsungの確認済み`MultiWindowManager.setStayFocusAndTopResumedActivityEnabled(boolean, boolean)`は端末全体の状態を変更する。`MultiWindowEnableController`は起動中ユーザーへvolatile stateを配布するため、履歴だけの設定として黙って流用してはいけない。`setStayFocusActivityEnabled(boolean)`はフォーカス保持とtop-resumed保持の両方を同じ値にするので、既存の別設定も巻き込む。

さらにMultiStarのBinder APIは許可パッケージとタスク管理権限を検証し、通常のLauncher呼び出しは許可リストにない。権限チェックの回避や、全体設定の一時変更による代用は実装していない。

音量全体、Samsungのアプリ別音量、アプリ別mute、AudioFocusを奪う処理は変更していない。音声の継続を描画と切り離すために、解除漏れのある永続消音を導入しない。

## 対象アプリごとのスコープを要求する試作の撤回

2026-10-18、対象アプリ内のDecorViewをフックする試作をローカルで作成したが、対象アプリごとにVectorのスコープを追加する必要があるため、利用者の要件に合わず撤回した。試作APKは端末にインストールしておらず、対象アプリのスコープも追加していない。試作の設定UI、アプリ内フック、Providerの仲介処理、バージョン番号の変更はソースから除去した。この撤回時点の端末と配布版は0.11.0だった。

この試作に対して行ったビルド・テストは、個別スコープ不要の実装や実機動作を検証したことにはならない。

## 個別スコープを不要にする共通バックエンド（0.12.0検証版）

当該端末のservices.jarでは、`CoreStateController.getCoreStates(int)`がユーザーの状態Bundleを複製し、`notifyCoreStatesChangedLocked(Runnable, int)`が各`WindowProcessController.mThread`へ`CoreStatesChangeItem`を送る。受信側は`ActivityThread.handleCoreStatesChanged(Bundle)`で`MultiWindowCoreState`を更新する。フォーカス保持のキーは`stay_focus_activity`。これらのクラス・メソッド・キーの存在と通常の配布経路は実装から確認済み。

この経路を利用する`RecentsFocusServer`を実装した。Launcherの既存の履歴ジェスチャー開始フックから、所有するタスクIDを渡す。system_serverは実タスク・ユーザー・ディスプレイ・ウィンドウモード・プロセスを自分で検証し、そのプロセス向けの状態コピーだけへ変更を補う。追加スコープは`system`一つで、ゲームなどへの個別スコープとフックは不要。実装したことと通知の実機動作確認は区別する。以下は検証時の要件であり、実測範囲は末尾にまとめる。

- 既にフォーカス喪失が抑制された後にフラグを変えるだけでは、遅れたコールバックが届くとは限らない。履歴開始と元のフォーカス通知の順序を確認する。
- 所有するタスク、プロセス、ユーザー、ディスプレイを照合し、分割画面・PiPや同じプロセスの別ウィンドウへの影響を避ける。
- `stay_top_resumed_activity`など他の項目と、端末全体の状態は変更しない。履歴中のSamsung側からの状態更新にも追従する。
- 履歴終了・キャンセル・機能無効化・Launcherまたは仲介プロセスの終了時には、変更前の古い値ではなく、その時点のSamsung標準状態へ戻す。
- 互換性不明や例外時は当該機能を無効化する。system_serverのクラッシュはホームのクラッシュとは影響が異なるため、ホーム用CrashGuardが同じように保護すると仮定しない。

## 新しいシステム側フックの読み込み制約

Vector 2.2 / 3112の手元の配布物内`framework/vector.dex`でも、ホットリロード処理はそのプロセスに既存のモジュール世代がなければ`is not loaded in`で非対応を返すことを確認した。既に動いているsystem_serverへ初めてスコープを追加することと、既に読み込んだモジュールの更新は別であり、ホームの再起動や通常のホットリロードで解決できるとは扱わない。

同じ配布物には遅延system_server注入の経路も存在するため、「どの環境でも端末再起動が必須」とは断定しない。今回、利用者の承認を得て`setprop ctl.restart zygote`によるソフトリブートを実施した。システムプロセスの交代後もカーネル稼働時間を維持し、既存のrootとVector 2.2 / 3112が残ることを確認した。システムへの再注入やroot化手法の変更、通常の端末再起動は行っていない。初回の追加スコープはVector内部名の取り違え（`android`）を修正して`system`へ切り替えた。

## 参照

- [Vector ConfigCache](https://github.com/JingMatrix/Vector/blob/master/daemon/src/main/kotlin/org/matrix/vector/daemon/data/ConfigCache.kt): システム用スコープの内部名とsystem_server向けモジュール配布。実機版3112の挙動とも照合。
- [現在の継続描画実装](recents-live.md)
- [AOSP Android 16 QPR2 RecentsTransitionHandler](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/libs/WindowManager/Shell/src/com/android/wm/shell/recents/RecentsTransitionHandler.java): `setInputConsumerEnabled`でフォーカスを取得する。Samsung側にも対応する処理がある。
- Samsung固有のクラス・分岐は当該端末から取得したframework.jar / services.jarで確認。逆コンパイルした本体や個人の画面・ログはリポジトリに含めない。

## 検証版の設計と操作

- `RecentsFocusServer`: 正確なSM-S948QのAndroid 16 fingerprintに限定。API 102の`onSystemServerStarting`で、ATMSの独自Binder問い合わせを登録する。既存のAndroid Binderトランザクション番号は置換しない。問い合わせ番号は`0x00f04f55`、専用interface tokenとモジュールのversionCodeを照合する。
- 読み込み時は無効。設定アプリでモードを選ぶか「今回のシステム稼働中に有効化」を押すまで、状態配布・フォーカス監視・Activity状態のフックは登録しない。システム再起動後に保存設定だけで自動有効化しない。
- 有効化はモジュール自身のUIDだけ、開始・終了はOne UI HomeのUIDだけに許可する。shell・root UID・通常の別アプリからは利用できない。これはroot環境全体に対する隔離を保証するものではない。
- `ClientLifecycleManager.scheduleTransaction(ClientTransaction)`で、対象の`IApplicationThread`に一致する場合だけ、`CoreStatesChangeItem.mState`のコピーへ`stay_focus_activity`を補う。`stay_top_resumed_activity`等は維持する。元のitemの状態は送信後のfinallyで復元し、別プロセスへ同じ値が漏れないようにする。
- 初回配布はジェスチャー開始の元処理より前に行う。モード変更中の履歴は解除し、新しい指定は次のジェスチャーから適用する。
- 元からMultiStarの保持が無効な場合に「通知しない」へ変えると、既に終わったフォーカス獲得時の`mStayFocus`初期化を再現できない。その条件では`preserve-unavailable`で適用しない。
- 終了・キャンセル・他の画面への移動・ソースActivityの状態変更・Launcher／設定Provider／ソースプロセスのBinder死亡で解除する。失われた終了通知への一度きりの安全弁として10分の上限を設ける。定期ポーリングはしない。
- 解除は現在の`getCoreStates(0)`を読み直して配布する。失敗時は新しい適用を禁止し、最大3回だけ復元を再試行する。
- 同じプロセスの別Activityも表示中、分割画面、PiP、別ユーザー、外部ディスプレイなどは適用しない。
- UIは「準備完了」「Android側が未読み込み」「有効化が必要」「設定送信済み（通知は未検証）」を区別する。Binder送信成功をゲームへのコールバック成功や消音成功と表示しない。

新しいフックに既存のホーム用CrashGuardが適用されると説明してはいけない。system_server起動後は毎回手動有効化が必要な構成で再適用を防ぐが、ブートループを完全に防ぐ保証ではない。

### 実機確認手順

1. 必要な場合のみ事前承認したソフトリブート等でAndroid側へ初回読み込みを行い、root／Vector／既存機能とバックエンドの無効状態を確認する。
2. 設定で継続描画と通知モードを有効化する。スコープは共通の`system`とOne UI Homeを使用し、検証アプリ自体は追加しない。
3. 同じ検証ActivityのCanvasフレーム・Activityフォーカス通知・音声再生状態を、標準／通知／抑制で比較する。ゲームの音が消えたことは別途確認する。
4. ホーム／戻る／カード復帰／別アプリ起動／キャンセル／画面ロック／機能無効化で復元を調べる。標準以外の向きとHome UpのON/OFFは別に確認する。
5. 通知に失敗した場合はモードを標準へ戻す。モジュールを無効化するかAndroidスコープを外せば次回起動後に新フックを読み込まない。稼働中に外しただけで既存フックが消えるとは扱わない。

### 現在の確認範囲

署名付きrelease APK、75件の単体テスト、Lintが成功した。新規の10件は所有者の交代、古い終了通知、ユーザー・ディスプレイ・マルチウィンドウの除外、権限、保持モードの適用条件を検証する。Androidの実際のBinder配送順、フォーカス通知、ゲームの音声を単体テストで検証したものではない。

Vectorのシステム用スコープは内部名`system`である。OSパッケージの`android`とは区別する。初回は取り違えて`android/0`を追加しており、ソフトリブート後もsystem_serverへロードされなかった。VectorのConfigCacheの`appPkg == "system"`分岐と`getModulesForSystemServer()`を確認して修正した。通常アプリのスコープへのフォールバックは行っていない。

## 2026-10-18の共通バックエンド実機確認

検証版0.12.0 / code 18、同じSM-S948Q / Android 16 / One UI 8.5。Vectorスコープは既存3ホストと共通の`system`のみ。検証用のアプリとドライバーはスコープに追加していない。

- **通知・縦向き:** 設定配布の後、検証Activityへ`WINDOW_FOCUS false`が届いた。テスト音声のAudioTrackはPLAYSTATE_PLAYING（3）からPLAYSTATE_PAUSED（2）へ変わり、その後もCanvasのフレームは進み続けた。
- **抑制・縦／横向き:** 履歴が保持された後もフォーカス喪失が通知されず、描画とテスト音声の再生状態が継続した。ホームへ出ると標準のフォーカス喪失とpauseが発生し、復元ログを確認した。
- **終了:** 設定アプリなど別の画面へ移動した際に対象Activityの状態変更で共通バックエンドの復元が走ることを確認した。
- **権限・読み込み:** システムスコープからの読み込みと手動有効化を確認した。Binderのonewayビットだけを拒否し、通常のシステム付加フラグを許可する。UID照合用のPackageManager呼び出し中だけ呼び出しidentityを退避し、検証するUID自体は元の値を保持する。

以上は同じ検証Activityでの通知・描画・AudioTrack状態の実測。ガルパ本体の音圧やPCMは測定しておらず、ガルパで消音できたとは主張しない。通知に反応して描画自体も止めるゲームはあり得る。10分のタイムアウト、プロセス死亡、複数ウィンドウや別ユーザー等の除外は、実装・単体テストと実機確認済みの範囲を混同しない。

- **標準・縦向き:** 手動設定を標準へ戻し、バックエンドの`disarmed`を確認。同じ検証Activityでフォーカス保持・描画・テスト音声の再生状態が継続し、以前の標準動作へ戻った。
- **確認後の端末:** モードは標準（無効）に戻した。検証用アプリとドライバー、端末上の調査用XMLを削除し、ホームへ戻した。system_serverのPIDは通知・抑制・標準の検証中を通じて維持された。端末全体の`stay_focus=true, stay_top_resumed=true`も維持された。

通知モードの横向きでもフォーカス喪失・AudioTrackの一時停止・Canvasの継続を確認した。Home Up OFFでも横向きの履歴保持と描画継続を確認した。戻る操作後のフォーカス獲得と、次のホーム移動でのフォーカス喪失も確認した。ただし、後述の横向きジェスチャーキャンセルには復元順序の問題が残っている。画面ロック、プロセス死亡、復元失敗時の再試行、10分の上限到達は実機未確認。

### キャンセル後の追跡（修正前の記録）

通知モードでジェスチャーを反転して元のアプリへ戻ると、次のホーム移動時にActivityのフォーカス喪失コールバックが欠けるケースを検出した。元のアプリがフォーカスを取り戻した後に保持設定を復元すると、DecorViewの`mStayFocus`の整合性が回復しない可能性がある。単純にinvalidateの前へ移した版でも再現したため、この時点では公開しない。標準モードへ戻し、Home Up ONを復元し、検証アプリを削除した。

実際の`AnimationSession.requestFinishRecentAnimation()`は、`willGoToRecents && dontFinishByCommand`以外の場合に`recentsAnimationAction.finish()`を呼ぶ。次の修正ではこの戻り先の判定前と`RecentsAnimationManager.finish()`前に復元する。元の終了処理・アニメーションは抑制・遅延しない。これでキャンセル後の次の通知まで正常になることを実機で再確認する。

### 12:37時点の再検証と次の候補

終了要求と公開finishの前へ復元を追加した版で、縦向きの反転キャンセル→元アプリ→ホームはHome Up ON/OFFとも通知が届いた。一方、横向きの反転キャンセルでは、元アプリのフォーカス獲得が復元より先になり、次のホーム移動でfalseが欠けた。縦向きの成功を全条件の解決とは扱わず、公開は保留する。確認後は標準モード・Home Up ONへ戻し、検証アプリを削除した。system_serverのPIDと端末全体の保持設定は維持された。

実装を追うと、非公開の`RecentsAnimationManager.onGestureEnd()`と`onSettledOnTarget(boolean, boolean)`は公開finishを経由せず、共通の`finishController(boolean, boolean)`からネイティブの`finishGestureTransition`を呼ぶ。次の候補はこの共通ゲートの前でも復元する。実APKで存在と引数を確認したメソッドのみを解決し、当該メソッドとDEX上の実際の呼び出し元をdeoptimize対象へ追加した。ネイティブ終了処理やアニメーションを抑制せず、待機も追加しない。候補のビルド成功は実機での修正確認とは区別する。

## 最終候補の確認（12:43）

共通終了ゲートへ移した候補でも、Home Up ONの横向きキャンセルは改善しなかった。実際のLauncherログで、`GestureTaskListView`のページ遷移完了→`LaunchTaskHelper.launchTask(ShellTransition.TaskInfo)`→コルーチン上の`ShellTransitionManager.startActivityFromRecents`→元タスクのフォーカス獲得→`RecentsAnimationManager.finish`の順序を確認した。アニメーションの終了コールバックからでは遅い。これはタイマーや描画の非表示で補う問題ではない。

修正版では、実APKで確認した`LaunchTaskHelper.launchTask`の前で、所有するフォーカスリースだけを終了する。当該メソッドと実際のDEX呼び出し元をdeoptimizeし、非互換の場合は既存の導入エラー処理で機能を停止する。タスクの選択・起動要求・アニメーション・描画は元の処理を維持する。

| 確認操作 | 結果 |
|---|---|
| Home Up ON・横向きの反転キャンセル→元アプリ→Home | 同じ修正版で2回確認。`true → false → true → false`が届く |
| Home Up ON・縦向きの反転キャンセル→元アプリ→Home | 同じ通知列を確認 |
| Home Up OFF・横向きの反転キャンセル→元アプリ→Home | 同じ通知列を確認 |
| 検証音声の状態 | 上記各操作でAudioTrackが`3 → 2 → 3 → 2`。通知に応じた一時停止・再開を確認 |
| 描画 | フォーカス喪失中もCanvasフレームが進む |

これは検証Activityでの結果で、実ゲームの消音を確認したものではない。全組合せや配送順序の数学的な保証でもない。異常終了・画面ロック・10分上限・復元失敗は実機未確認として残す。

最終のコード確認で、新しいジェスチャーの開始時に前のリースの復元が失敗した場合、以後の開始を中止するガードも追加した。エラー状態を新しい所有者で隠したり、古い所有者の復元再試行を妨げたりしないための変更である。この失敗条件を実機で強制発生させる試験は行っていない。最終APKを上書きしホームを再読み込みしたが、system_serverへ読み込み済みのコードは再起動していないため、この追加ガードの実機実行は未確認。通常の通知・復元経路は変更していない。

最終状態は標準モード・Home Up ON。検証アプリ2個と端末の一時XMLを削除した。今回の5分枠では端末・ソフト再起動は行わず、system_serverの同一PIDとグローバルな`stay_focus=true, stay_top_resumed=true`を確認した。署名済みreleaseビルド、75件の単体テスト、release Lintが成功。互換性を限定した実験版として配布し、他のOS・端末への一般対応とは扱わない。
