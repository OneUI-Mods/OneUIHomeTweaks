# 履歴の継続描画とフォーカス通知（調査中）

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

利用者の希望は「フォーカス喪失を通知する／通知しない」を**履歴画面の間だけ**選択できること。履歴以外では既存のMultiStar設定を維持する。加えて、対象のゲームなどを一つずつVectorのスコープに追加する操作は不要にする。現時点ではこの設定・修正は未提供。

Samsungの確認済み`MultiWindowManager.setStayFocusAndTopResumedActivityEnabled(boolean, boolean)`は端末全体の状態を変更する。`MultiWindowEnableController`は起動中ユーザーへvolatile stateを配布するため、履歴だけの設定として黙って流用してはいけない。`setStayFocusActivityEnabled(boolean)`はフォーカス保持とtop-resumed保持の両方を同じ値にするので、既存の別設定も巻き込む。

さらにMultiStarのBinder APIは許可パッケージとタスク管理権限を検証し、通常のLauncher呼び出しは許可リストにない。権限チェックの回避や、全体設定の一時変更による代用は実装していない。

音量全体、Samsungのアプリ別音量、アプリ別mute、AudioFocusを奪う処理は変更していない。音声の継続を描画と切り離すために、解除漏れのある永続消音を導入しない。

## 対象アプリごとのスコープを要求する試作の撤回

2026-10-18、対象アプリ内のDecorViewをフックする試作をローカルで作成したが、対象アプリごとにVectorのスコープを追加する必要があるため、利用者の要件に合わず撤回した。試作APKは端末にインストールしておらず、対象アプリのスコープも追加していない。試作の設定UI、アプリ内フック、Providerの仲介処理、バージョン番号の変更はソースから除去した。端末と配布版は0.11.0のままである。

この試作に対して行ったビルド・テストは、個別スコープ不要の実装や実機動作を検証したことにはならない。

## 個別スコープを不要にする候補（未実装・未検証）

当該端末のservices.jarでは、`CoreStateController.getCoreStates(int)`がユーザーの状態Bundleを複製し、`notifyCoreStatesChangedLocked(Runnable, int)`が各`WindowProcessController.mThread`へ`CoreStatesChangeItem`を送る。受信側は`ActivityThread.handleCoreStatesChanged(Bundle)`で`MultiWindowCoreState`を更新する。フォーカス保持のキーは`stay_focus_activity`。これらのクラス・メソッド・キーの存在と通常の配布経路は実装から確認済み。

この経路を利用し、system_serverで履歴の継続描画対象プロセスだけへ状態のコピーを配布する方式を候補とする。実現できれば対象アプリ内のXposedフックや個別スコープを必要とせず、追加のスコープは共通のAndroid側だけにできる。ただし、次の条件を満たす実装・実測がまだ必要であり、解決済みとは扱わない。

- 既にフォーカス喪失が抑制された後にフラグを変えるだけでは、遅れたコールバックが届くとは限らない。履歴開始と元のフォーカス通知の順序を確認する。
- 所有するタスク、プロセス、ユーザー、ディスプレイを照合し、分割画面・PiPや同じプロセスの別ウィンドウへの影響を避ける。
- `stay_top_resumed_activity`など他の項目と、端末全体の状態は変更しない。履歴中のSamsung側からの状態更新にも追従する。
- 履歴終了・キャンセル・機能無効化・Launcherまたは仲介プロセスの終了時には、変更前の古い値ではなく、その時点のSamsung標準状態へ戻す。
- 互換性不明や例外時は当該機能を無効化する。system_serverのクラッシュはホームのクラッシュとは影響が異なるため、ホーム用CrashGuardが同じように保護すると仮定しない。

## 新しいシステム側フックの読み込み制約

Vector 2.2 / 3112の手元の配布物内`framework/vector.dex`でも、ホットリロード処理はそのプロセスに既存のモジュール世代がなければ`is not loaded in`で非対応を返すことを確認した。既に動いているsystem_serverへ初めてスコープを追加することと、既に読み込んだモジュールの更新は別であり、ホームの再起動や通常のホットリロードで解決できるとは扱わない。

同じ配布物には遅延system_server注入の経路も存在するため、「どの環境でも端末再起動が必須」とは断定しない。ただし、既存の一時root・稼働中Vectorに対し、安全に新規モジュールを読み込める手順は確認できていない。システムプロセスの再注入、端末・ソフト再起動、root環境の変更は実施していない。追加スコープも未設定。

## 参照

- [現在の継続描画実装](recents-live.md)
- [AOSP Android 16 QPR2 RecentsTransitionHandler](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/libs/WindowManager/Shell/src/com/android/wm/shell/recents/RecentsTransitionHandler.java): `setInputConsumerEnabled`でフォーカスを取得する。Samsung側にも対応する処理がある。
- Samsung固有のクラス・分岐は当該端末から取得したframework.jar / services.jarで確認。逆コンパイルした本体や個人の画面・ログはリポジトリに含めない。
