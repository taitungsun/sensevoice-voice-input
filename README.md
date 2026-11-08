# sensevoice-voice-input

[繁體中文說明](#繁體中文)

Offline voice input for Android. Speak Chinese or English; Chinese comes out in Traditional characters. Everything runs on the phone; the network is used only once, to download the model.

Made for low-end keypad phones such as the Qin F21 Pro, and for seniors and anyone who finds typing hard. Works on arm64 phones with Android 8.0 or later. Forked from [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service).

## Features

- Works as Android's system voice input, e.g. from the voice key in TT9
- Spoken punctuation: say "comma", "period", "new line" and so on; no automatic full stop
- Large text, full keypad navigation, TalkBack support
- No ads, no tracking

## Install

1. Install the APK from [Releases](../../releases).
2. In the app, download **SenseVoice Small** (239 MB).
3. Settings → Languages & input → Voice input → choose this app. If there is no such page:

   ```
   adb shell settings put secure voice_recognition_service io.github.taitungsun.sensevoice/app.dsh.asr.AsrRecognitionService
   ```

With TT9, the keyboard also needs microphone access, and other voice input apps must be disabled, or TT9 opens them instead.

## Background running

The app stays in the background (with a notification) to keep the model loaded. Don't restrict its background activity, or the model reloads (about 9 s) every time.

## Signing

Certificate SHA-256: `bf:f0:94:b6:2c:5f:55:c5:11:fa:5f:af:c0:77:33:54:4a:ed:b3:05:4c:4c:8f:61:33:b1:05:63:40:86:96:db`

## Building

JDK 17, Android SDK (platform 36), Gradle 8.13+. Put `sherpa-onnx-1.13.8.aar` from [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) in `app/libs/`, set `sdk.dir` in `local.properties`, set `SV_KS_FILE`, `SV_KS_PASS`, `SV_KEY_ALIAS`, `SV_KEY_PASS`, then run `gradle assembleRelease`.

## License

Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). The SenseVoice Small model (FunAudioLLM, Alibaba Group) is under the [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE).

---

## 繁體中文

Android 離線語音輸入。說中文或英文都可以，中文會輸出繁體字。辨識全程在手機上完成，只有第一次下載模型時需要網路。

為 Qin F21 Pro 等低階按鍵手機，以及長輩和不方便打字的人設計。適用 Android 8.0 以上的 arm64 手機。改編自 [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service)。

### 特色

- 作為系統語音輸入服務，在 TT9 等輸入法按語音鍵即可使用
- 口述標點：說「逗號」「句號」「問號」「驚嘆號」「冒號」「分號」「頓號」「刪節號」「換行」；句尾不自動加句號
- 字體大、可完全用按鍵操作、支援 TalkBack
- 沒有廣告，不收集資料

### 安裝

1. 從 [Releases](../../releases) 下載 APK 並安裝。
2. 在 App 中下載 **SenseVoice Small**（239 MB）。
3. 系統設定 → 語言與輸入設定 → 語音輸入 → 選擇本 App。沒有這個設定頁時：

   ```
   adb shell settings put secure voice_recognition_service io.github.taitungsun.sensevoice/app.dsh.asr.AsrRecognitionService
   ```

使用 TT9 時，TT9 本身也需要麥克風權限；若裝有其他語音輸入 App 須先停用，否則 TT9 會開啟那個 App。

### 背景執行

本 App 會在背景常駐（並顯示通知）以保持模型載入。請勿限制它的背景執行，否則每次說話都要重新載入模型（約 9 秒）。

### 簽章

憑證 SHA-256：`bf:f0:94:b6:2c:5f:55:c5:11:fa:5f:af:c0:77:33:54:4a:ed:b3:05:4c:4c:8f:61:33:b1:05:63:40:86:96:db`

### 自行建置

需要 JDK 17、Android SDK（platform 36）與 Gradle 8.13 以上。將 [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) 的 `sherpa-onnx-1.13.8.aar` 放到 `app/libs/`，在 `local.properties` 設定 `sdk.dir`，以環境變數提供 `SV_KS_FILE`、`SV_KS_PASS`、`SV_KEY_ALIAS`、`SV_KEY_PASS`，再執行 `gradle assembleRelease`。

### 授權

Apache-2.0，詳見 [LICENSE](LICENSE) 與 [NOTICE](NOTICE)。SenseVoice Small 模型（FunAudioLLM，阿里巴巴集團）採用 [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)。
