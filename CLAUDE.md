# PRA106 Launcher

PORMIDO PRA106（Android 13・QCM6125・車載ディスプレイオーディオ）向けの自作ホームランチャー。

## 前提・制約
- root化とUSB ADBは不可（`ro.secure=1`、USBはホスト専用）。純正のSystemUIバーは消せない。
- 使える権限は通常アプリ・ユーザー補助（AccessibilityService）・オーバーレイ（SYSTEM_ALERT_WINDOW）の範囲だけ。
- ローカルにAndroid SDKがないため、ビルドの検証は GitHub Actions（main に push → Releases にAPK）で行う。
- 署名は `app/debug.keystore` で固定している（上書きインストールするため）。差し替えないこと。

## 構成
- `MainActivity` / `AppAdapter`：ホーム（アプリのグリッド・ドック・並べ替え・非表示）
- `StatusBarService`：重ねて表示する簡易バー（右＝運転席側から 戻る・ホーム・前のアプリ・SKIP・Bluetooth・Wi-Fi再検索、左端に時計）
- `NativeHomeTileService`：クイック設定パネルの「純正ホーム」タイル
- `LauncherAccessibilityService`：唯一のユーザー補助サービス。GlobalAction の実行、前面アプリの追跡、AutoSkipEngine へのイベント受け渡し
- `AutoSkipEngine`：YouTube/Netflix/Prime Video のスキップボタンを自動で押す
- `Prefs`：設定の保存（全機能で共有）

## 開発の流れ
1. 機能担当（`android-feature`）が、担当ファイルを分けて並列に実装する
2. `ux-critic` がダメ出しする（コードは触らない）
3. 「高」の指摘は必ず直す。「中・低」はユーザーに確認してから直す
4. push して GitHub Actions のビルドを通し、APK とデモ（iPhone用HTML）を更新する

## 規約
- Kotlin で、Jetpack Compose は使わない（View + RecyclerView）。新しい依存ライブラリは極力追加しない。
- コメントとUIの文言は日本語。
- 文字列リソースは機能ごとに分ける：`strings_home.xml` / `strings_bar.xml` / `strings_autoskip.xml`
- AutoSkip の禁止事項：「次のエピソード」「Next Episode」は押さない。Netflix は画面右上だけを検知対象にする。
