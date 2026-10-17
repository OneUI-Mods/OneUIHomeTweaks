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
