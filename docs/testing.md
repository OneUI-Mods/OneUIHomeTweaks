# 実機検証と復旧

## 確認記録（2026-10-16、開発中）

- APKのインストール、Vector runtime API 102でのモジュール読み込み。
- `DEX_VERIFIED`（12メソッド／4クラス）、実際の色読み取り元、2つのObserver、色読み取り置換とローカル通知。
- ホームの黒文字表示を実機画像で確認。One UIのsystem設定の保存値そのものは変更していない。ドロワーも黒／白の切り替えを画像確認し、最後に保存されていた標準自動へ復元した。
- Home Upの動的ロード経路を確認。4ページ構成で`x=0/0.3333/0.6667`、`xStep=0.3333`を送信。MultiPictureの診断でも受信・変化回数・`SCREEN_FIXED`を確認。
- MultiPicture側の画面数は当初3だった。実ホーム4ページとの不一致では画像が中間になる。画像選択自体とページ数は壁紙アプリ側の設定で管理する。
- 音量UIは実際にはSound Assistantの追加パネルだった。標準パネルへの修正が表示に届かないため、動的プラグイン対応を追加。`VOLUMESTAR_HOOK_READY callers=49`と`VOLUMESTAR_COLOR mode=2`を確認。ロック解除後、通常・展開パネルの両方でライト（明るい背景／黒い前景）とダーク（暗い背景／白い前景）を画像確認。`VOLUMESTAR_BLUR mode=1 preset=106`／`mode=2 preset=121`と一致し、設定変更だけで再構築できた。システム指定へ戻すと元のダーク表示へ戻ることも確認。ユーザーが保存していたダーク指定へ復元した。
- プレビューの対象クラス探索と既存ホームSurfaceのミラー配置を確認。別エンジン方式は廃止。最終コードでホーム2ページ目からプレビューを開き、BACKで戻る遷移を録画確認。ページ位置は1/3を保持し、仮ホームから0への通知が混入しないこと、背景が別ページに切り替わらないことを確認。遷移中のSamsung標準のアイコン描画は残る。
- Material 3の標準BottomNavigationViewで4画面へ分割。system barの余白と日本語表示を実機確認。
- 全体OFFで3対象がOFF、HomeのみOFFでSystemUI／DressRoomがACTIVEのままであることを実行報告で確認。
- 色ポリシー4件、ページ正規化4件、起動保護5件の計13単体テスト、Android Lint、APK署名検証が成功。公開ブランチのGitHub Actionsでもビルド・テスト・Lintが成功。
- Android 17 / One UI 9、別機種、すべての静止画、他のXposedモジュールとの全組み合わせは未検証。

## 手動確認

1. Vector未適用では時計アイコン／未確認となることを確認。対象を有効にして再読み込みし、応答を確認する。
2. 全体をオフ→オン。対象アプリ別にオフ→オン。他の対象と保存したモードが保たれるか確認する。
3. ホームの黒／白／自動を変更して保存。`STATE/READ/DISPATCH`を確認。自動の場合はMultiPictureのAndroid表示色の設定を確認する。色情報がnullの場合は標準へ戻る。
4. ドロワーは標準自動で元の外観を維持し、固定色の解除で元へ戻るか確認する。
5. 壁紙の「画面位置で制御」と画面数を合わせ、ホーム各ページで画像が揃うか確認する。フォルダ・Discover・編集・ロック・設定内の仮ホームでは位置通知しないことも確認する。
6. 2ページ目などからWallpaper and styleを開いて背景を比べる。戻る遷移で別画像へ切り替わらないか確認する。プレビューにはnativeの1ページ目のアイコン配置が表示されるが、背景は実ホームの描画を使う。
7. 音量をシステム／ライト／ダークにして保存。通常・展開パネルを確認し、他のSystemUI画面の配色が変わらないことを確認する。
8. 必要に応じて静止画でも既存の文字色処理を確認する。テストのために壁紙を切り替えた場合は元のコンポーネントへ戻す。

```sh
adb logcat -s OneUiLiveColors:I '*:S'
adb shell getprop ro.build.version.oneui
adb shell getprop ro.build.fingerprint
adb shell dumpsys wallpaper
adb shell dumpsys package com.sec.android.app.launcher
adb shell dumpsys package com.samsung.android.app.homestar
adb shell settings get system need_dark_font
adb shell settings get system need_dark_statusbar
```

`dumpsys`には個人情報が含まれる場合があるため、公開Issueへ丸ごと貼らない。画面画像や端末シリアルも自動公開しない。

## 停止・ロールバック

通常は全体または対象スイッチをオフ。登録されたフックも除去する場合はVectorでモジュール／対象スコープを無効にして対象プロセスを起動し直す。

```sh
adb shell su -c '/data/adb/lspd/cli modules disable net.ryuya_dev.oneuilivecolors'
adb shell am force-stop --user 0 com.sec.android.app.launcher
adb shell am start --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME
```

SystemUIやWallpaper and styleの再読み込みは一時的に画面を閉じる。アプリ内の対象別ボタンを使う。端末再起動・データ消去は不要。

起動保護ファイルは各対象のprivate files内の`net.ryuya_dev.oneuilivecolors.guard`。クラッシュ保護で停止した場合は原因を直し、アプリの「クラッシュ保護を解除」でこのファイルだけを削除して、対象を再読み込みする。自動解除はしない。直前PIDに対応するApplicationExitInfoが`CRASH`、`NATIVE_CRASH`、`ANR`のいずれかの場合だけ連続回数を加算する。正常な手動再起動、終了理由が未確定の起動、90秒安定稼働した起動、10分以上離れた起動、端末起動し直し後は連続回数をリセットする。

Launcherデータや壁紙画像の削除、既存root／Vectorの再設定は行わない。

## 0.4.1 release配布の確認

専用RSA 4096鍵で署名したreleaseビルドを作成し、`apksigner verify`が成功。APKと実機の`pkgFlags`にDEBUGGABLEがないことを確認した。実機のdebug版を削除してrelease版を導入し、アプリ自身の保存モードとVectorの3スコープを復元。release版の設定画面の起動と状態応答を確認した。フック実装は外観を検証した0.4.0と同じであり、署名変更によるSystemUIの再読み込みはこの移行では行っていない。

既にdebug版を導入している一般利用者は、アプリの設定を控えてからアンインストールし、release版をインストールしてVectorを再設定する。以後は同じ配布鍵で上書き更新する。

## 0.5.0 modern API 102移行の確認

専用鍵で署名した0.5.0 release版を0.4.1へ上書きし、保存設定とVectorの3スコープが維持された。APK内の登録は`META-INF/xposed/java_init.list`、`scope.list`、`module.prop`のみで、`minApiVersion=102`と`targetApiVersion=102`を確認した。API 82 jar、legacyクラス参照、`assets/xposed_init`は含まれない。

対象プロセスを個別に起動し直し、Vector 2.2のmodernローダーで次を確認した。

- Launcher: `MODULE_LOADED api=102`、`DEX_VERIFIED`、`PAGE_HOOK_READY`、`DRAWER_HOOK_READY`、`READ`、`DISPATCH`。
- SystemUI: `MODULE_LOADED api=102`、`VOLUME_HOOK_READY`、`VOLUMESTAR_HOOK_READY`、`VOLUMESTAR_COLOR`。
- Wallpaper and style: `MODULE_LOADED api=102`、`PREVIEW_HOOK_READY`、`PREVIEW_HOME_MIRROR_ATTACHED`。

クラッシュ保護解除はSystemUIが使う`/data/user_de/0`も対象に修正し、保護解除後にSystemUIの各フックが再度初期化されることを確認した。端末の再起動は行っていない。

## 0.5.1 起動保護の誤検知修正

0.5.0では、短時間に行った手動の対象プロセス再読み込みについて、Androidの終了理由がまだ取得できない場合も未完了起動として加算していた。これによりLauncherとSystemUIが`blocked=true`となり、Vectorがモジュールを読み込んでいてもフックが意図どおり停止したことを実機で確認した。

0.5.1では直前PIDと終了理由を照合し、明示的なクラッシュ系理由だけを加算するよう修正した。保護ファイルを解除後、LauncherとSystemUIを90秒以内に2回ずつ意図的に再読み込みし、双方が`blocked=false`、`attempts=1`のまま稼働することを確認した。再読込後のログではLauncherの色・ページ・ドロワーフック、SystemUIの標準音量・Sound AssistantフックがAPI 102で再初期化された。状態画面はLauncherとSystemUIから`稼働中`の応答を受信した。端末の再起動は行っていない。


## 0.6.0 One UI Home Tweaksと履歴機能（2026-10-17）

旧アプリから同じID・鍵で上書きし、One UI Home Tweaksの表示名とホーム／調整のアイコン、5言語の新設定、履歴2機能の状態カードを確認した。Vectorのスコープ追加は不要で、履歴機能はOne UI Homeだけに適用する。

横向き固定の一時検証用Activity（ネットワーク・root等の権限なし）をインストールし、修正前に横向きの履歴を再現した。機能オンでは3120×1440の横向きActivityから1440×3120の縦向き履歴へ移り、オフ・全体オフ・Home対象オフでは3120×1440へ戻る。Home対象オンで再び縦向きになることも確認した。アプリに戻るとそのアプリの横向きを維持した。

ホームバー非表示では、RecentsのInsetsSourceにあるnavigationBarsがvisible=falseとなり、解除時はvisible=trueへ復元することを確認した。当初はバー非表示によりSamsungの没入表示判定がホームスワイプを止めたため、フォーカス中のRecentsに限定した標準のバー非表示中ジェスチャー許可を追加した。修正後は非表示中の履歴から下端スワイプ1回でホームへ戻れた。

OSのナビゲーション設定画面で実際に3ボタンへ切り替え、navigation_mode=0とラジオ選択を確認した状態でも、履歴下端の戻る・ホーム・履歴ボタンが残った。OSのジェスチャーヒントをオフ（navigation_bar_gesture_hint=0）にし、アプリの非表示オプションをオフ・オンしてもグローバル設定が変化せず、バーが再表示されないことを確認した。検証後のOS設定は元のnavigation_mode=2、横と下のスワイプ、ヒント=1へ復元した。

機能の新規インストール既定値は2つともオフ。対象実機ではユーザーの依頼に合わせ2つともオンで残している。検証用アプリは作業後にアンインストールした。端末再起動・システムパーティション変更は行っていない。18単体テストとrelease Lint・署名検証を実施。2ボタン方式、Samsungの下から3領域スワイプ方式、DeX・外部画面・マルチウィンドウの実機操作、Android 17以降は未検証であり、対象外／不明な構成では補正しない。

## 場所別のホームラベル配色（0.7.0）

1. ホームのアプリ名を「場所ごとに自動」にして保存。明暗が混在するライブ壁紙を表示し、明るい場所と暗い場所で黒・白が同時に使われることを確認する。ステータスバーは他モジュールの設定のままであることを確認する。
2. ページを移動し、壁紙の画像が落ち着いてから文字色が各位置で再判定されることを確認する。Home Upフリースタイルも同様に確認する。
3. アイコンの配置を変え、旧位置ではなく新しいラベル位置で判定されることを確認する。複数行・表示倍率・切り詰めラベルも確認する。
4. ドロワーとフォルダー内部、Recents、他アプリを開き、場所別配色が適用されないことを確認する。従来のドロワー色指定は独立した機能として確認する。
5. 黒固定／白固定／無効／全体オフ／ホーム対象オフへ変更し、場所別のcustom色が残らないことを確認する。静止画への変更は標準色へ戻ることを確認する。
6. 取得失敗・画像寸法不一致・横向き・ロック・マルチウィンドウでは標準へ戻るか適用されないことを確認する。
7. 色通知のない画像変更・長い遷移・ウィジェットに重なるラベルは制約がある。常時追従したと記録しない。

実機ではLauncherが既に持つREAD_FRAME_BUFFER権限による壁紙Surfaceだけを取得する。保存済みの旧ステータスバー設定は適用しない。端末・SystemUIの再起動、他の文字色モジュールの無効化は不要。

### 2026-10-17 実施記録

- SM-S948Q / Android 16 / Launcher 17.5.03.27 / Home Upフリースタイルで、標準WallpaperColorsがnullでも場所別配色が動作。あるページで黒19・白2、別のページで黒7・白1とファインダーの黒を同時に確認。個人のホーム画像は公開しない。
- 同一プロセス内で場所別→無効→場所別を変更し、無効時はアプリ名とファインダーが標準の白へ戻り、再有効化時は局所配色へ戻ることを画面と応答から確認。旧status設定が残っていても、本モジュールの設定取得結果はOFFで、ステータスバーの別モジュールには設定変更を行っていない。
- 画面外ページの色を解除しない処理と、局所画素の安定待ちを追加。連続録画ではユーザーの別アプリ操作と重なったため、動画によるちらつきの完全解消は未確認。全ての壁紙遷移時間・Home Upアニメーションでの解消を保証しない。
- 単体テスト24件成功、release Lintはエラー0・警告30（非公開APIの利用等を含む）。追加root権限付与、システムパーティション変更、端末再起動は実施していない。

- 未訪問ページの追加対策：実機応答で`initial=25 fallback=0`を確認。25個の初回表示が判定後のフェードへ進み、3秒の標準色フォールバックは発生していない。訪問済みページだけが改善したという実機フィードバックを受けた対策で、全アニメーションの動画確認は引き続き未完了。

- 0.7.1：ファインダーの親からリソース名searchIconのImageViewだけを選び、文字と同じimageTintを適用する。元のtintの復元も実装。端末が別アプリで使用中のため、追加直後の画面比較は未実施。
