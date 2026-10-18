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

利用者の希望は「フォーカス喪失を通知する／通知しない」を選択できること。適用範囲を確認してから実装する。現時点では設定・修正とも未提供。

Samsungの確認済み`MultiWindowManager.setStayFocusAndTopResumedActivityEnabled(boolean, boolean)`は端末全体の状態を変更する。`MultiWindowEnableController`は起動中ユーザーへvolatile stateを配布するため、履歴だけの設定として黙って流用してはいけない。`setStayFocusActivityEnabled(boolean)`はフォーカス保持とtop-resumed保持の両方を同じ値にするので、既存の別設定も巻き込む。

さらにMultiStarのBinder APIは許可パッケージとタスク管理権限を検証し、通常のLauncher呼び出しは許可リストにない。権限チェックの回避や、全体設定の一時変更による代用は実装していない。

音量全体、Samsungのアプリ別音量、アプリ別mute、AudioFocusを奪う処理は変更していない。音声の継続を描画と切り離すために、解除漏れのある永続消音を導入しない。

## 参照

- [現在の継続描画実装](recents-live.md)
- [AOSP Android 16 QPR2 RecentsTransitionHandler](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/libs/WindowManager/Shell/src/com/android/wm/shell/recents/RecentsTransitionHandler.java): `setInputConsumerEnabled`でフォーカスを取得する。Samsung側にも対応する処理がある。
- Samsung固有のクラス・分岐は当該端末から取得したframework.jar / services.jarで確認。逆コンパイルした本体や個人の画面・ログはリポジトリに含めない。
