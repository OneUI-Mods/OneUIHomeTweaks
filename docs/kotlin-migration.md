# Kotlinへの移行（2026-10-30）

Android側の実装、JVMテスト、実機用テストコードをKotlinへ移しました。ビルドはAndroid Gradle Pluginの組み込みKotlin / JVM 17を使用します。既存のパッケージ名、設定キー、provider authority、module entry、署名の識別は引き継ぎます。SDK・対象OS・機能の対応範囲を、この移行だけで拡大したものではありません。

C++・Goのソースは変更していません。JNIを持つアプリは、移行前のJava宣言とコンパイル後のクラスからメソッド名・JVM descriptor・static指定を比較しています。

## 動作の維持

- Android 16と17の既存の分岐・検証済み契約を維持します。`docs/compatibility/android16-source.json`がある場合、そのJavaパスとハッシュは過去の固定コミットの記録です。現在のKotlinソースへ書き換えません。
- Javaの数値変換、不変コレクション、nullを受ける復旧経路、recordのコンストラクタ補正・文字列表現を維持します。永続化した設定や確認用識別値を別の形式に変更しません。
- 配布用APKへ検証用Activityや実機テストを含めません。デスクトップ専用のDEX調査ツールはAndroidアプリの実装対象に含めません。

## 確認

```sh
./gradlew :app:compileReleaseKotlin :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

既存JVMテスト117件とrelease lintを確認しています。実機用コードはコンパイルの確認と、端末上での実行を区別します。この初期ビルド確認の段階では実機操作は未実施でした。後続の実機確認と区別し、最新の範囲は下記を参照してください。

Kotlinの整形は`ktfmt --kotlinlang-style`を使用します。生成コードを更新した場合も整形とビルドを確認してください。配布時は既存のリリース手順を使い、同じ鍵で署名したrelease variantを検証します。移行ブランチのビルド成果物を、そのまま公開リリースとして扱わないでください。

## エミュレータによる再調査（2026-10-30）

Google APIsのarm64エミュレータ、Android 16（API 36）とAndroid 17（API 37）で、rootを使わずに確認しました。このエミュレータ確認段階では、実機と既存のADB接続は操作していません。

- 設定・ライセンスなど、このアプリに存在する通常Activityの起動、タブの切り替え、Activity再生成を確認。
- 通常表示、ダーク表示＋文字サイズ150%、release variantでのダーク表示＋文字サイズ200%を確認。ライセンス一覧があるアプリでは検索・一覧の再配置・詳細への移動も実行。
- release variantの試験にはローカル専用の試験署名コピーを使用。配布用の署名や公開APKとは区別しています。検証用runnerは別APKです。
- 移行前コミットからJava版を別worktreeでビルドし、同じエミュレータの通常表示とJVMテストで比較。
- 実際の設定スイッチ1個を変更し、プロセス終了後の保持を確認。同じ設定をJava版で変更してからKotlin版へ上書き更新し、値が残ることも確認し、最後に元へ戻しました。全設定の組合せを網羅した試験ではありません。

このエミュレータ試験の結果は、rootなしでアプリ側の画面と設定が動くことを確認したものです。Samsung SystemUI／One UIホーム／Home Up／Camera／Camera Assistant、Vectorのフック実行、root権限、実際の録画・通話音声・物理デバイスの経路は検証していません。Kotlin化前と全機能が同一と断定するものではありません。

## 実機で判明したProxy引数のnull（2026-10-30）

Android 17のSamsung SystemUIで、クイックパネルのState.getValue()をJava Proxy経由で呼ぶと、InvocationHandlerの引数配列はnullになります。移行後のPresentationが非nullを要求しており、SystemUIがクラッシュしました。クラッシュ保護の作動を確認した上で修正しています。

Proxyの呼び出し規約をAndroid依存のないStatePresentationHandlerへ分け、getValue／hashCode／toStringの引数なし呼び出しと、equalsの参照同一性を維持しました。実際のJava Proxyを使う回帰テスト3件を追加し、既存117件と合わせて120件成功しています。Samsung固有の一覧変換・キャッシュ・失敗時の純正値への復帰は既存処理を維持します。修正版の署名済みreleaseを実機へ上書きし、端末上のAPKハッシュも一致しました。端末のSamsung製Compose Stateとインストール済みreleaseのハンドラをapp_processへロードするコマンド試験で、引数なしgetValue・hashCode・toStringと参照同一性equalsが成功しました。その後、GalaxyReloadのロック維持コマンドでSystemUIのみ読み込み直し、実際のComposeクイックパネルを複数回開閉して再発がないことを確認しました。作動した保護は解除済みです。

## DEX走査の条件式（2026-10-30）

ロック維持のSystemUI再読み込みとクイックパネル描画で、Proxy例外の再発がないこととクラッシュ保護の解除を確認しました。その後VolumeStarのメソッド走査で、元のJava条件式のORグループの括弧が欠け、引数配列の先頭を無条件に参照する移行不具合を検出しました。

VolumeStarのblur呼出し判定と、DIY位置・音量のDEX callsite判定を明示的な小さな判定関数へ分離。参照種別・対象所有者・メソッド名を確認した後にのみ引数を調べる順序を維持します。Javaの括弧が意味を持つAND/ORの78箇所と、移行後のpatternPrefix条件も追加で走査しました。8件の回帰テストを追加し、One UI Home TweaksのJVMテストは128件です。

最終の署名済みreleaseを実機へ上書きし、ロックを維持したSystemUI再読み込み後にクイックパネルを複数回開閉しました。SystemUIのPIDは開閉中に安定し、VolumeStarを含むSystemUIのモジュール状態はACTIVEへ復帰。Adaptive Bar・Capture・Home TweaksのSystemUI保護はいずれも非停止です。更新後の設定は退避した値と一致し、kernel boot ID・system_server・ホームPIDは変わっていません。ソフトリブートと音声再生は実施していません。

ロック解除後のアプリ開閉ジェスチャー、One UIホーム内でのフォルダ操作など、今回触れていないホストプロセスの実機動作まで確認したものではありません。

## 解除後のAndroid 17実機確認（2026-10-30）

継続使用中と同じ鍵の署名済みrelease版を上書きし、メイン画面が前面に表示されることをUIツリーで確認しました。ライセンス一覧の表示も確認しています。機能スイッチの変更を目的としたON／OFF操作はせず、全アプリの設定を確認前後で退避・照合しました。

One UIホームのみをアプリ再起動し、フォルダ内からアプリ起動→上スワイプでフォルダへ復帰→フォルダを閉じる操作を確認。アプリ内の純正クイックパネル部品のプレビューも表示されました。設定の全組合せや配置異常の再現は未確認です。

今回のGUI確認中はOS・system_server・SystemUIを再起動せず、録画開始・音声再生も行っていません。初期のビルド確認や先行するSystemUIの読み込み直しとは別の確認段階です。画面が表示できることだけで全機能を確認済みとは扱いません。Android 16はエミュレータと既存契約までで、Android 16実機の再試験は行っていません。

## クイックパネルの再描画修正（0.19.2 / 2026-10-31）

実機ログで検出したComposeのcallbackキャッシュの型ずれを修正しました。移行前Java版にも存在した変更ビット操作を見直し、StateとModifierの比較処理を純正と同じ順序で呼び出します。既定Modifierの置き換えも扱います。回帰テスト6件を追加し、JVMテスト計134件とrelease lint、継続署名のrelease検査に成功しました。Android 16アダプターは変更していません。切り分けと実機確認の詳細は[クイックパネルの開発記録](quick-panel.md)を参照してください。
