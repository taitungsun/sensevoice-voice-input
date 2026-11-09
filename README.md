# sensevoice-voice-input

[繁體中文說明](#繁體中文)

Offline voice input for Android. When you speak Chinese, the words come out in Traditional Chinese characters; English works too. Recognition runs entirely on the phone, so your voice and text never leave it; the network is used only once, to download the model.

The app becomes Android's system voice input, so you can dictate with the voice key of a keyboard such as TT9. It is made for low-end keypad phones such as the Qin F21 Pro, and for seniors and anyone who finds typing hard. It works on arm64 phones with Android 8.0 or later. The app's screens are in Traditional Chinese. Forked from [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service).

## Features

- Traditional Chinese output
- Spoken punctuation: say "comma", "period", "question mark", "new line" and so on
- No automatic full stop at the end of a sentence; say "period" when you want one
- The model stays loaded, so text appears soon after you stop speaking
- Switch between Chinese and English without reloading; English comes out in normal case, not all capitals
- A short buzz when recording starts, so you know when to speak
- Large text, full keypad navigation, TalkBack support
- Only the SenseVoice Small model, downloaded from HuggingFace; unneeded models and setup tools removed

## Install

1. Install the APK from [Releases](../../releases), or add `https://github.com/taitungsun/sensevoice-voice-input` in [Obtainium](https://github.com/ImranR98/Obtainium) to get updates automatically.

2. Open the app and download **SenseVoice Small** (239 MB; Wi-Fi recommended).

3. Allow microphone access.

4. Settings → Languages & input → Voice input → choose this app. If your phone has no such page, use adb:

   ```
   adb shell settings put secure voice_recognition_service io.github.taitungsun.sensevoice/app.dsh.asr.AsrRecognitionService
   ```

5. With TT9, the keyboard itself also needs microphone access.

If another app with its own voice input screen (such as whisperIME) is installed, TT9 opens that app instead. Disable or remove it:

```
adb shell pm disable-user --user 0 org.woheller69.whisper
```

## Privacy and permissions

No ads, no tracking; nothing is collected or sent.

| Permission         | Used for                                                          |
| ------------------ | ----------------------------------------------------------------- |
| Microphone         | Voice input                                                       |
| Network            | Downloading the model only; you can turn it off afterwards        |
| Run at startup     | Loading the model after boot, so the first sentence needs no wait |
| Foreground service | Keeping the model in memory                                       |
| Notifications      | Android requires a notification for a foreground service          |
| Vibration          | The short buzz when recording starts                              |

Don't restrict the app's background activity: Android would stop it, and the model would reload (about 9 s) before every sentence. Turning off network access has no such effect.

## Signing

All release APKs are signed with the same key. Certificate SHA-256:

```
bf:f0:94:b6:2c:5f:55:c5:11:fa:5f:af:c0:77:33:54:4a:ed:b3:05:4c:4c:8f:61:33:b1:05:63:40:86:96:db
```

Check it with `apksigner verify --print-certs <APK file>`.

## Building

You need JDK 17, the Android SDK (platform 36) and Gradle 8.13 or later.

1. Put `sherpa-onnx-1.13.8.aar` from [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) in `app/libs/`.
2. Set `sdk.dir` in `local.properties`.
3. Set the signing environment variables `SV_KS_FILE`, `SV_KS_PASS`, `SV_KEY_ALIAS`, `SV_KEY_PASS`.
4. Run `gradle assembleRelease`.

## License

Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Built on [dsh-asr-service](https://github.com/Soodok/dsh-asr-service), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and [OpenCC](https://github.com/BYVoid/OpenCC), all Apache-2.0.

The model is not included in this project or the APK; the app downloads it from HuggingFace. SenseVoice Small is released by FunAudioLLM (Alibaba Group) under the [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE).

---

## 繁體中文

Android 離線語音輸入。中文、英文都能說，中文輸出繁體字。辨識全程在手機上完成，語音和文字都不會傳出手機；只有第一次下載模型時需要網路。

安裝後即成為 Android 的系統語音輸入，在 TT9 等輸入法按下語音鍵就能使用。為 Qin F21 Pro 等低階按鍵手機，以及長輩和不方便打字的人設計，適用 Android 8.0 以上的 arm64 手機。改編自 [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service)。

### 特色

- 輸出繁體中文
- 口述標點：說「逗號」「句號」「問號」「驚嘆號」「冒號」「分號」「頓號」「刪節號」「換行」會轉成標點符號
- 句尾不自動加句號，需要時說「句號」即可
- 模型常駐記憶體，說完很快就出字
- 中文、英文切換不必重新載入；英文輸出一般大小寫，不是全部大寫
- 開始錄音時短震動，提示可以說話
- 字體大、可完全用按鍵操作、支援 TalkBack
- 只使用 SenseVoice Small 模型，從 HuggingFace 下載；移除用不到的模型與設定工具

### 安裝

1. 從 [Releases](../../releases) 下載 APK 並安裝；或在 [Obtainium](https://github.com/ImranR98/Obtainium) 加入 `https://github.com/taitungsun/sensevoice-voice-input`，即可自動更新。

2. 開啟 App，下載 **SenseVoice Small**（239 MB，建議使用 Wi-Fi）。

3. 允許麥克風權限。

4. 系統設定 → 語言與輸入設定 → 語音輸入 → 選擇本 App。沒有這個設定頁時，可用 adb：

   ```
   adb shell settings put secure voice_recognition_service io.github.taitungsun.sensevoice/app.dsh.asr.AsrRecognitionService
   ```

5. 使用 TT9 時，TT9 本身也需要麥克風權限。

若手機上還裝有其他提供語音輸入畫面的 App（例如 whisperIME），TT9 會開啟那個 App。請停用或移除它：

```
adb shell pm disable-user --user 0 org.woheller69.whisper
```

### 隱私與權限

沒有廣告，不追蹤，不收集也不回傳任何資料。

| 權限     | 用途                                   |
| -------- | -------------------------------------- |
| 麥克風   | 語音輸入                               |
| 網路     | 只用於下載模型，下載後可關閉           |
| 開機啟動 | 開機後自動載入模型，第一次說話不必等待 |
| 前景服務 | 讓模型常駐記憶體                       |
| 通知     | 前景服務依規定須顯示通知               |
| 震動     | 開始錄音時的短震動                     |

請勿限制本 App 的背景執行，否則系統會關閉它，每次說話前都要重新載入模型（約 9 秒）。關閉網路權限沒有這個影響。

### 簽章

Releases 中的 APK 都以同一把金鑰簽章，憑證 SHA-256：

```
bf:f0:94:b6:2c:5f:55:c5:11:fa:5f:af:c0:77:33:54:4a:ed:b3:05:4c:4c:8f:61:33:b1:05:63:40:86:96:db
```

可用 `apksigner verify --print-certs <APK 檔案>` 核對。

### 自行建置

需要 JDK 17、Android SDK（platform 36）與 Gradle 8.13 以上。

1. 將 [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) 的 `sherpa-onnx-1.13.8.aar` 放到 `app/libs/`。
2. 在 `local.properties` 設定 `sdk.dir`。
3. 以環境變數提供簽章資料：`SV_KS_FILE`、`SV_KS_PASS`、`SV_KEY_ALIAS`、`SV_KEY_PASS`。
4. 執行 `gradle assembleRelease`。

### 授權

Apache-2.0，詳見 [LICENSE](LICENSE) 與 [NOTICE](NOTICE)。以 [dsh-asr-service](https://github.com/Soodok/dsh-asr-service)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 與 [OpenCC](https://github.com/BYVoid/OpenCC) 為基礎，皆採 Apache-2.0。

語音模型不包含在本專案與 APK 中，由 App 從 HuggingFace 下載。SenseVoice Small 由 FunAudioLLM（阿里巴巴集團）釋出，採用 [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)。
