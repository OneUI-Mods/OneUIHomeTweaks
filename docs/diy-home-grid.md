# DIY Homeのアイコン整列（0.9.0 / 0.10.0）

## 目的と対象

DIY Homeを有効にしたまま、ホーム上のアプリアイコン・ショートカット・フォルダだけを通常ホームと同じグリッドへ揃える。ウィジェット、積み重ねウィジェット、ステッカー、DIY Home自体の設定は変更しない。通常のグリッドは端末の設定から取得し、7×7などを固定値として埋め込まない。

「ホーム」タブのスイッチは初期OFF。全体・One UI Homeのスイッチも尊重する。既存の対象を最寄りの空いたセルへ整列し、利用者がDIYエディターで動かすときもセルへ吸着させる。

## 確認した実装と選定根拠

調査・実機確認環境: Galaxy S26 Ultra SM-S948Q / Android 16 / One UI 8.5 / One UI Home 17.5.03.27 / Home Up 17.5.00.30 / Vector 2.2（API 102）。対象端末・OSの既存の互換性ゲートを維持する。

Home UpはLauncherに動的ロードされる。PageBridgeが実際に観測するFreeGridFastRecyclerViewのクラスローダーを使う。Home Up本体をXposedスコープへ追加する必要はない。

実機で通常グリッド7×7、DIYグリッド7×9、通常セル190×339 px、座標差分(0,0)、1×1アイコンの内側余白(9,61)を取得した。これらは実測値であり、ソースの固定定数ではない。

| 確認済みの型・メソッド | 根拠と用途 |
|---|---|
| FreeGridViewModel.getItemPositionData() | 通常ホーム用CellLayoutInfo・グリッドからDIY上の位置へ変換する寸法・余白を作る |
| FreeGridCellLayout.createLayoutParams(FreeGridItem) | モデル座標を読む描画経路。実行中だけgetX/getYを投影 |
| FreeGridCellLayout.createLayoutParams(View,int,int,int,int,float,boolean) | 追加Viewのレイアウト経路。対象アイコンだけ純正paramsへ投影 |
| FreeGridCellLayout.updateViewWithoutReattach(View,FreeGridItem) | 既存View更新の座標参照に限定して投影 |
| FreeGridViewModel.includePoint(FreeGridItem,Point) | フォルダ等のモデル座標による当たり判定を描画位置と合わせる |
| FreeGridCellLayout.prepareSyncPosition(FrameControlStickerView) | 純正の編集開始時。移動量の持ち越しをリセット |
| FreeGridCellLayout.syncPosition(FrameControlStickerView) / translateTarget(StickerView,float,float,boolean) | 純正エディターの移動処理。対象モデルがアプリ・フォルダの場合だけ吸着を補う |
| FreeGridCellLayout.fitTargetView(StickerView) | 純正の編集枠を実際のアイコン位置と一致させる |
| FreeGridViewModel.updateToDropPoint(FreeGridItem,Point,int,Point) | ドロップ時の座標を補完。(-1,-1)の無効位置は維持し、保存は純正に任せる |
| FreeGridCellLayout$FreeGridLayoutParams.setup(Point,float) | 描画と編集の両方が参照する純正座標を設定。単なるViewのtranslationで済ませない |

FreeGridItem.createCommonItemData()は保存にgetPortX()/getPortY()を使う。描画と当たり判定に使うgetX()/getY()と区別し、後者の投影は確認済みの読み取り専用UIメソッドの実行中だけ有効にする。保存・移行・向き変更時の補正・シリアライズへ投影を流さない。

編集時はFrameControlStickerViewのgetTargetPosition()が使う実際のPointFフィールドとControlViewを検証して参照する。純正APIの整数への切り捨てだけを毎回使うと、編集枠をセルへ戻したときに1 px未満の移動が失われる。小数を含めた余りをジェスチャーごとに保持し、少しずつ指を動かしてもセル境界を越えられるようにする。

DEXの実呼び出し参照から関連UI・編集メソッドを特定し、インライン化の影響を避けるためdeoptimizeする。この版の実機では48個のメソッド／コンストラクタを確認した。名前だけが存在することをもって将来版の互換性を保証しない。

## 配置計算・保存・停止条件

GridSnapPlanはページ内の対象に重複しないセルを割り当てる。未変更アイコンの以前の割り当てを維持し、大きいフォルダは複数セルを予約する。移動中は同じページの他の対象セルを避け、近い空きセルへ吸着する。ウィジェットの領域は予約しないため、DIY Home本来のウィジェットとの重なりを許容する。これは全アイテムを通常ホームの占有ルールへ戻す機能ではない。

ON/OFF自体では保存済み配置を更新しない。利用者が対象を動かした場合は、吸着した座標を純正の編集・履歴・保存処理が扱う。OFFは保存中の自由配置を表示する操作で、利用者の編集を取り消すものではない。純正「元に戻す」もDIY Homeの履歴仕様に従い、編集前の全DBを復元する機能ではない。

互換性検証とDEX処理はバックグラウンドで行う。定期ポーリングはなく、設定・ページ・レイアウト・モデルの更新を契機に動く。メイン画面・縦画面・LTR・通常ホームの実体に限定し、プレビュー、横画面、RTL、他ディスプレイは純正配置を使う。容量不足はページ単位で純正へ戻す。契約不一致や例外では本機能のフックだけを解除し、保存座標で再レイアウトする。

## 2026-10-17の検証

確認済み:

- 同一署名のrelease版で上書き更新し、Launcherを再読み込み。端末再起動は実施していない。
- 整列ON/OFF、ページ移動、通常ホームへ戻った後の整列。
- 整列したアイコンから設定アプリが起動すること、フォルダが開閉できること。
- 純正DIYエディターでアプリとフォルダを別セルへドラッグし、編集枠が追従すること。
- 約8秒かけた低速ドラッグでも隣の空きセルへ移ること。ログで約0.25 pxの移動と小数の持ち越しを確認。
- 状態タブで、専用のチェックアイコンと「稼働中」バッジが表示されること。
- 純正の「元に戻す」で元のセルへ戻ること。テスト編集後は1フォルダの保存座標にセル吸着相当の微差が残った。全配置の無変更を確認したという意味ではない。
- 編集後を基準として、編集なしのOFF→ONとページ移動の前後でDB比較。item、item_group、multi_display_position、inversion_grid_positionの配置関連フィールドに差分なし。ウィジェットの配置・サイズも不変。
- スナップ計算・空きセル移動を含む35件の単体テストとrelease Lint。配置計算の100個の生成ケースで範囲・重複・入力順の不変性も確認。

未検証の組み合わせ:

- ドロワーからの新規追加・別ページへのドロップ、複数対象の同時移動、ステッカーとの混合選択。
- 横画面・RTL・容量不足・大きいフォルダの実機操作（計算・ガードの実装はある）。
- 他のHome Up／Launcher版、他機種、Android 17／One UI 9。

実機検証範囲は上記に限定する。バックアップしたDB・設定、Samsung APK、逆コンパイル結果、個人のホーム画像は公開リポジトリや配布物に含めない。

## 明示的な永続保存（0.10.0）

「整列した配置を保存」は、整列ON/OFFとは独立した操作。まず現在の全ページのモデル・座標・形状・グリッドから計画を作り、変更件数と対象ページ数を表示する。確認後にも同じ計画であることをSHA-256 fingerprintとモデル所有者の実体で確かめる。配置やグリッドが変化していれば保存せず、プレビューのやり直しを求める。編集中、対象の取得失敗、容量不足では保存しない。

保存は実機で確認した `FreeEditInfo(int,Integer,Integer,Integer,Integer,Integer,Float,Float,Integer,boolean)` と `FreeGridViewModel.updateFreeGridProperty(List,boolean,boolean)` を使う。idと新しいx/yだけを指定し、ページ・サイズ・倍率・回転・重なり順はnull、削除はfalseとする。純正の履歴とリポジトリ保存を通し、DBへ直接UPDATEしない。適用時の例外では純正APIで座標の復元を試み、保存確認が取れない場合は成功と表示しない。

`OneUI.db.item` は読み取り専用で開き、保存前にid/type/container_id/position_x/position_yの一致を確認し、保存後に新座標を確認する。確認は利用者の保存操作に伴う最大4回の短い再試行に限り、常時ポーリングしない。

コマンドは管理アプリの非公開領域に保存し、nonce・同一boot・20秒の期限・一度だけの取り出しで管理する。ContentProviderは実際の呼び出しUIDがOne UI Homeの場合だけ、取り出しと結果の受信を許す。一般アプリやブロードキャストから保存を実行できない。

実機では46件（アプリ15、フォルダ26、ショートカット2、ディープショートカット3）の保存を確認した。保存前後で変化した `item` の列はposition_x/position_yのみ。ほかのitem列、item_group、multi_display_position、inversion_grid_positionに差分はなかった。その後の再プレビューは変更0件となり、整列フックをOFFにしても保存後の配置を表示できた。確認後は整列ONへ戻した。root自体の解除や端末再起動は実施していない。

バックアップしたDBは非公開の検証資料として保持し、リポジトリへは含めない。保存を取り消す場合は純正のDIY編集履歴の範囲で「元に戻す」を使う。フックOFFやアプリのアンインストールは永続保存の取り消しにはならない。

## ログと復旧

```sh
adb logcat -s OneUIHomeTweaks
```

GRID_SAVE preview / saved / error、DIY_GRID loading / active / off、DIY_GRID_DEX_VERIFIED、DIY_GRID_EDIT、DIY_GRID_PAGE_UNAVAILABLE、DIY_GRID_DISABLEDで状況を追える。移動ログはジェスチャー先頭の少数回に限定する。

問題がある場合は整列スイッチをOFFにする。フック自体の例外停止後に再試行する場合は、原因を解消してからアプリ内でOne UI Homeを再読み込みする。通常のスイッチ変更ではプロセス再読み込みも端末再起動も不要。

## 2026-10-27: 縦画面でアイコンだけが詰まる不具合

Android 17 / One UI Home 18.0.07.53 / Home Up 18.0.00.36 / モジュール0.18.0で調査した。ウィジェットは正しい位置にあり、アイコン・フォルダだけが画面上部へ詰まり、一部が画面右端の外へ移動する。利用者によればホームのプロセス再起動で直るが、調査中は再起動していない。

最初にlogcat、ActivityのView階層、配置DBとWAL、モジュール設定を非公開の証跡へ保存した。縦画面のセル領域は1330×2375 pxなのに、表示上のアイコン間隔は横394 px・縦117 pxだった。保存済みの座標をこの寸法と表示原点で既存のGridSnapPlanへ渡すと、2ページ分52個のアイコン・フォルダの座標集合が実機の崩れと完全一致した。

保存した後、利用者の許可を得て「DIY Homeのアイコン整列」だけをOFF→ONにした。OFFでは保存座標へ戻り、ONでは同じ崩れが再発した。前後でLauncherのPIDは不変で、itemとitem_groupの配置関連列も不変だった。ウィジェットの配置は変更していない。比較後は元のONへ戻した。

この表示崩れに整列処理が関与することは確認できた。ただし、横長の寸法が最初に渡される契機や、Samsung側のどの状態が不整合になるかは未特定である。直前に横画面を使ったことまでは確認できておらず、回転だけが発生条件とは扱わない。実機Home UpのSHA-256は保管済みAndroid 17 APKと一致し、そのgetItemPositionDataがCellLayoutInfoのサイズから寸法を作る経路を照合した。

修正案では、ネイティブの向き情報と測定済みの実際のセル領域を使い、グリッド外寸が収まらない間は投影・ドラッグの吸着・配置の明示保存を許可しない。設定を自動OFFにしたり、保存データを書き換えたりせず、その間は純正の座標を表示する。異なる純正余白とセルごとの丸め誤差は許容し、固定の行列数や画面比率へ置き換えない。RecyclerViewがページを切り離した場合は同じViewModelの測定済みページを使う。向き・dp寸法・密度も観測キーへ含め、不整合の診断ログは寸法が変わったときだけ記録する。定期ポーリングは追加しない。

実測した不整合、正常な縦グリッド、画面サイズ変更、異なる行列数、純正余白、未測定・不正値、整数オーバーフローを回帰試験へ追加した。全112件のJVM試験、release Lint、releaseビルドが成功した。この時点では修正版のインストールとホスト再読み込みは行っていない。Android 16の実機再試験や発生契機の再現が済んだとは記載しない。
