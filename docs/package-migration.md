# パッケージ名の移行

旧: `net.ryuya_dev.oneuilivecolors`、新: `dev.ryuya.oneuihometweaks`。配布署名鍵は継続する。

実機の移行では、旧版を停止して設定XMLを非公開のroot専用ディレクトリに保全し、新版をインストールしてから`modes.xml`、`app_ui.xml`、`grid_save.xml`をコピーする。所有UID・アクセス権・SELinuxラベルを新版へ合わせる。ホストの診断状態・キャッシュ・プロファイルは移さない。

新旧の設定内容が一致すること、新版から設定を読めること、Vectorのスコープが維持されることを確認する。旧版の無効化と新版の有効化を済ませてから対象プロセスを再読み込みする。移行が完了するまで旧版はアンインストールしない。認証設定やsystem_serverは変更しない。

Android 16・17の契約は変更せず、パッケージ名とprovider authority・アプリ側の参照のみを更新する。
