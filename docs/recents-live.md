# 標準の向きで履歴の継続描画（0.11.0）

## 削除した機能

履歴を縦向きへ固定するスイッチ、詳細・状態カード、方向要求のフックを削除した。共通viewportのRenderNode再描画、遷移中のSurface行列の置換、insetsの補完、spring、handoff barrierと旧遷移トレースも削除した。以前の調査は[履歴資料](recents-motion.md)として保存している。

`ConfigProvider`の旧`recents_portrait`列は、APK更新中にまだ動いている旧LauncherフックへOFFを伝える互換用の値0だけを残す。旧保存値は参照せず、管理画面の起動時に削除する。新しいLauncherフックはこの列を読まない。

## 残したライブ描画

`RecentsLiveTileHooks`はSamsung標準の`AnimationSession.requestFinishRecentAnimation`の後で、所有する最後の通常タスクのcontrollerだけを保持する。履歴の方向要求やHome Upのレイアウト・並び順は変更しない。

`RecentsLiveSurface`は、実際の履歴Rootの寸法・回転、native playerの`getHomeToWindowMatrix`、remote source boundsを検証する。`RecentsLeashSpace`はこの実basisに一致するsource空間を選び、`RecentsLiveGeometry`はsnapshot回転・縮小率、実際のPaintのshader、Viewのglobal行列を合成する。既存のカード枠へ最後のアプリのSurfaceを配置し、source cropと角丸を合わせる。

実機の標準横向き履歴では、Rootもsourceも3120×1440、回転1、player basisはidentityだった。縦向きRootへ横向きsourceが入る場合は既存native basisのquarter-turnを使う。寸法だけから親Surfaceの変換を推測しない。Rootの寸法・回転が途中で変わった場合は保持を解放する。

壁紙のblurはRootの実ViewRoot Surfaceを親にした所有effectへ分離する。元のblur半径・カード描画・app alphaは退避／終了時に復元し、所有effectを除去する。定期的なsnapshot採取やアプリのライフサイクル抑制は行わない。動画・音声の再生ポリシーも変更しない。

## 傾き表示の微小な遠近成分

Home UpのTilt stackは、選択カードが停止した後もshaderに約`4.77e-8`の遠近成分を残した。遠近成分が厳密に0でない行列をすべて拒否すると、横向きの停止したカードまで静止画のままになる。

合成行列を`Mglobal × Mshader × Msource`の順で作り、定数分母を1に正規化する。source矩形全体で分母`w`の最小値・最大値と、アフィン分子の最大ノルムを四隅から求める。アフィン近似との差の上限は

```text
maxNorm(numerator) × max(|minW − 1|, |maxW − 1|) / minW
```

で評価できる。分母が正で、有限で、上限が画面上0.5ピクセル以内の場合だけアフィン行列を使う。線形分母と凸な分子ノルムの境界を使うため、四隅の値が近いというだけで内部の大きな誤差を見逃さない。分母が0をまたぐ場合、本当の遠近変形、大きな誤差ではnative snapshotへ退避する。アプリの描画は続け、表示可能な行列へ戻るとライブ表示へ戻す。

実測shaderを使った回帰テストで矩形内部も比較し、通常の遠近変形と分母の符号変化が引き続き拒否されることを確認する。

## 入力と終了

保持中のHome／Back領域はRootの実`WindowInsets`から取得する。縦・横とも同じ状態機械を使い、キャンセル・複数指・古いtouch streamは操作を起こさない。生の座標軸がwindowと違う場合は、所有するnative task listへ渡すコピーだけを補正する。

おすすめアプリは起動受付の`ShellTransitionManager.getLaunchOptions`で所有holdを解放する。直接のカード起動は`getActivityLaunchOptions`でも解放し、0.10.1の「おすすめアプリの起動が待たされる」修正を維持する。Home、Back、別タスクの前面化、画面OFF、機能OFF、native invalidateでも解放する。

## フォーカス通知と音声

MultiStarのマルチフォーカスが有効だと、描画が続くアプリへのフォーカス喪失通知が抑制され、ゲームの音声も残る場合がある。実機の有効状態とSamsungのDecorView分岐を確認した。[フォーカス通知の調査記録](recents-focus.md)を参照。0.11.0には通知方法の選択や消音処理は含まれない。

## 検証と制限

SM-S948Q、Android 16 / One UI 8.5、Launcher 17.5.03.27、Home Up 17.5.00.30、Vector 2.2 / API 102が対象。Home Up ON／OFFで標準の横向き履歴のCanvasアニメーションと実VideoView / SurfaceViewの更新を別々の画像で確認した。実際のYouTube再生ポリシー、すべてのHome Up配置、逆横向き・180度回転、他機種、Android 17 / One UI 9まで検証したものではない。

試験では最初にnative Rootの寸法・回転と実display回転を読み、各ジェスチャーをそのwindowから実displayへ変換する。画面の向きが変わったのに固定したADB座標を使った試行や、再利用した検証Activityが前の向きを保持した試行は成功扱いにしない。

[実機確認・無効化・復旧](testing.md)も参照。

0.12.0では、共通のシステムスコープを使って履歴中のフォーカス通知を選ぶ実験機能を追加した。ゲームごとのスコープ追加は不要。[通知の仕組み、実機結果と未確認項目](recents-focus.md)を参照。標準モードは従来の動作を維持する。
