# sensevoice-voice-input

離線語音轉文字：辨識全程在手機上完成，語音與文字都不會外傳，只有第一次下載模型時需要網路。安裝後即成為 Android 的系統語音辨識服務，在 TT9 等輸入法按下語音鍵就能使用。

本專案改編自 [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service)，針對 Qin F21 Pro 等效能較低的按鍵手機最佳化，也適用於 Android 8.0 以上、arm64 架構的手機。

## 與原專案的差異

- 輸出繁體中文，你說什麼詞就打出什麼詞，不會自動改掉用詞
- 口述標點：說「逗號」「句號」「問號」「驚嘆號」「冒號」「分號」「頓號」「刪節號」「換行」會轉成標點符號；英文可說 comma、period、full stop、question mark、exclamation mark、colon、semicolon、new line
- 不在每句結尾自動加句號（SenseVoice 原本會加），需要時說「句號」即可
- 模型常駐記憶體：載入一次約需 9 秒（F21 Pro），之後每句不必重新載入。常駐約佔 300 MB 記憶體，待機時不耗 CPU，通知列會顯示「離線語音輸入已就緒」
- 中文與英文切換不必重新載入模型；英文輸出改為一般大小寫（原本全為大寫）
- 開始錄音時短震動，提示可以開始說話
- 使用 4 個執行緒解碼，錄音期間不做即時預覽，讓結果更快出來
- 只使用官方原版 SenseVoice Small（2024-07-17），一律從 HuggingFace 下載
- 移除用不到的功能：其他辨識模型（Paraformer、串流模型、粵語微調版）、中國鏡像站下載、Shizuku／root 一鍵設定
- 獨立的套件名稱 `io.github.taitungsun.sensevoice`，可與原專案同時安裝

## 實測速度（Qin F21 Pro，MT6761）

| | 每句等待時間 |
|---|---|
| whisperIME（Whisper base） | 約 8–12 秒 |
| 本專案（SenseVoice Small） | 說完後約 1–2 秒 |

## 安裝

1. 從 [Releases](../../releases) 下載 APK 並安裝。
2. 開啟「SenseVoice 語音輸入」，到「辨識模型」下載 **SenseVoice Small**（239 MB，建議使用 Wi-Fi）。
3. 授予麥克風權限。
4. 將本 App 設為系統語音辨識服務：系統設定 → 語言與輸入設定 → 語音輸入。若手機沒有這個設定頁，可用 adb：

   ```
   adb shell settings put secure voice_recognition_service io.github.taitungsun.sensevoice/app.dsh.asr.AsrRecognitionService
   ```

5. 使用 TT9 時，呼叫語音輸入的輸入法本身也需要麥克風權限。

### 搭配 TT9 的注意事項

如果手機上還裝有其他提供語音輸入畫面的 App（例如 whisperIME），TT9 會優先開啟那個 App，而不使用系統語音辨識服務。這時需要停用或移除該 App：

```
adb shell pm disable-user --user 0 org.woheller69.whisper
```

## 隱私與權限

辨識全程在手機上進行，語音和文字都不會傳出手機。App 沒有廣告，也不收集或回傳任何使用資料。

| 權限 | 用途 |
|---|---|
| 麥克風 | 語音輸入時使用 |
| 網路 | 用於下載 SenseVoice 語音模型；下載完成後可關閉 App 的網路權限，不影響使用 |
| 開機啟動 | 開機後自動載入模型，第一次說話不必等待 |
| 前景服務 | 讓模型常駐記憶體，說完後能快速出字 |
| 通知 | 前景服務依規定須顯示通知（通知列的「離線語音輸入已就緒」） |

如果限制了本 App 的背景執行，常駐會被系統關閉，每次說話前都要重新載入模型（約 9 秒）。關閉網路權限沒有這個影響。

Releases 中的 APK 以同一把金鑰簽章，憑證 SHA-256 指紋：

```
bf:f0:94:b6:2c:5f:55:c5:11:fa:5f:af:c0:77:33:54:4a:ed:b3:05:4c:4c:8f:61:33:b1:05:63:40:86:96:db
```

可用 `apksigner verify --print-certs <APK 檔案>` 核對。

## 自行建置

需要 JDK 17、Android SDK（platform 36）與 Gradle 8.13 以上。

1. 從 [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) 下載 `sherpa-onnx-1.13.8.aar`，放到 `app/libs/`。
2. 在 `local.properties` 設定 `sdk.dir`。
3. 簽章資料以環境變數提供：`SV_KS_FILE`、`SV_KS_PASS`、`SV_KEY_ALIAS`、`SV_KEY_PASS`。
4. 執行 `gradle assembleRelease`。

## 授權

程式碼採用 Apache License 2.0，詳見 [LICENSE](LICENSE) 與 [NOTICE](NOTICE)。

- 原專案：[Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service)（Apache-2.0）
- 辨識引擎：[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）
- 繁簡轉換字典：[OpenCC](https://github.com/BYVoid/OpenCC)（Apache-2.0）

語音模型不包含在本專案與 APK 中，由 App 從 HuggingFace 下載。SenseVoice Small 由 FunAudioLLM（阿里巴巴集團）釋出，採用 [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)。

---

## English

Offline speech-to-text: recognition runs entirely on the phone, and neither audio nor text ever leaves it. The network is needed only once, to download the model. After installation the app becomes Android's system speech recognition service, so you can dictate by pressing the voice key of a keyboard such as TT9. Speak Mandarin and get Traditional Chinese text, or speak English.

Forked from [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service) and optimized for low-end keypad phones such as the Qin F21 Pro. It also works on other arm64 phones with Android 8.0 or later. The app's own screens are in Traditional Chinese.

### Changes from the original project

- Traditional Chinese output (Taiwan character forms). Your words are kept as spoken, never swapped for other vocabulary
- Spoken punctuation: say 逗號, 句號, 問號, 驚嘆號, 冒號, 分號, 頓號, 刪節號 or 換行 in Chinese, or comma, period, full stop, question mark, exclamation mark, colon, semicolon or new line in English
- No automatic full stop at the end of each sentence (SenseVoice adds one); say 句號 or "period" when you want one
- The model stays loaded in memory: loading takes about 9 s on the F21 Pro, then every sentence is fast. This uses about 300 MB of RAM and no CPU while idle; the notification shows 離線語音輸入已就緒 ("offline voice input ready")
- Switching between Chinese and English needs no reload; English comes out in normal case instead of all capitals
- A short vibration when recording starts, so you know when to speak
- Decoding uses 4 threads and skips live previews while recording, so the result arrives sooner
- Only the original SenseVoice Small model (2024-07-17), always downloaded from HuggingFace
- Unneeded features removed: other models (Paraformer, streaming models, the Cantonese fine-tune), the China mirror download, Shizuku/root one-tap setup
- Its own package ID, `io.github.taitungsun.sensevoice`, so it can be installed next to the original

### Speed (Qin F21 Pro, MT6761)

| | Wait per sentence |
|---|---|
| whisperIME (Whisper base) | about 8–12 s |
| This project (SenseVoice Small) | about 1–2 s after you stop speaking |

### Installation

1. Download the APK from [Releases](../../releases) and install it.
2. Open 「SenseVoice 語音輸入」, go to 「辨識模型」 (models) and download **SenseVoice Small** (239 MB; Wi-Fi recommended).
3. Grant microphone access.
4. Select the app as the system voice input service: Settings → System → Languages & input → Voice input. If your phone has no such page, use adb:

   ```
   adb shell settings put secure voice_recognition_service io.github.taitungsun.sensevoice/app.dsh.asr.AsrRecognitionService
   ```

5. With TT9, the keyboard itself also needs microphone permission.

If another app with its own voice input screen (such as whisperIME) is installed, TT9 opens that app instead of the system service. Disable or remove it:

```
adb shell pm disable-user --user 0 org.woheller69.whisper
```

### Privacy and permissions

Recognition runs entirely on the phone; audio and text never leave it. There are no ads, and no usage data is collected or sent.

| Permission | Used for |
|---|---|
| Microphone | Voice input |
| Network | Downloading the SenseVoice model; you can turn off the app's network access afterwards without affecting use |
| Run at startup | Loading the model after boot, so the first sentence needs no wait |
| Foreground service | Keeping the model in memory, so text appears quickly |
| Notifications | Android requires a notification for a foreground service (離線語音輸入已就緒) |

If you restrict the app's background activity, Android stops it and the model must reload (about 9 s) before every sentence. Turning off network access has no such effect.

Release APKs are all signed with the same key. Certificate SHA-256 fingerprint:

```
bf:f0:94:b6:2c:5f:55:c5:11:fa:5f:af:c0:77:33:54:4a:ed:b3:05:4c:4c:8f:61:33:b1:05:63:40:86:96:db
```

Check it with `apksigner verify --print-certs <APK file>`.

### License

Code is Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). The model is not included in this project or the APK; the app downloads it from HuggingFace. SenseVoice Small is released by FunAudioLLM (Alibaba Group) under the [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE).
