# sensevoice-voice-input

離線語音輸入服務，使用 SenseVoice 模型，輸出繁體中文（台灣用語）。安裝後成為 Android 系統的語音辨識服務，TT9 等輸入法的語音鍵可以直接使用。語音只在手機上處理，不會上傳。

本專案 fork 自 [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service)，針對 Qin F21 Pro 這類效能較低的按鍵手機調整，也適用於其他 Android 8.0 以上、arm64 的手機。

## 與原專案的差異

- 輸出繁體中文，並轉換為台灣用語（例如「列印」「軟體」「影片」）
- 口述標點：說「逗號」「句號」「問號」「驚嘆號」「冒號」「分號」「頓號」「刪節號」「換行」會轉成標點符號；英文可說 comma、period、full stop、question mark、exclamation mark、colon、semicolon、new line
- 模型常駐記憶體：載入一次約需 9 秒（F21 Pro），之後每句不必重新載入。常駐約占 300 MB 記憶體，待機時不耗 CPU，通知列會顯示「離線語音輸入已就緒」
- 中文與英文切換不必重新載入模型；英文輸出改為一般大小寫（原本全為大寫）
- 開始錄音時短震動，提示可以開始說話
- 使用 4 個執行緒解碼，錄音期間不做即時預覽，讓結果更快出來
- 介面文字改為繁體中文（台灣用語）
- 預設模型為官方原版 SenseVoice Small（2024-07-17）；另可選粵語微調版（WSYue）
- 系統語言為繁體中文時，直接從 HuggingFace 下載模型（不使用中國鏡像站）
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
4. 將本 App 設為系統語音辨識服務：系統設定 → 語言和輸入法 → 語音輸入。若手機沒有這個設定頁，可用 adb：

   ```
   adb shell settings put secure voice_recognition_service io.github.taitungsun.sensevoice/app.dsh.asr.AsrRecognitionService
   ```

5. 使用 TT9 時，呼叫語音輸入的輸入法本身也需要麥克風權限。

### 搭配 TT9 的注意事項

如果手機上還裝有其他提供語音輸入畫面的 App（例如 whisperIME），TT9 會優先開啟那個 App，而不使用系統語音辨識服務。這時需要停用或移除該 App：

```
adb shell pm disable-user --user 0 org.woheller69.whisper
```

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

語音模型不包含在本專案與 APK 中，由 App 從 HuggingFace 下載。SenseVoice Small 由 FunAudioLLM（阿里巴巴集團）釋出，採用 [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)；粵語微調版來自 [ASLP-lab/WSYue-ASR](https://huggingface.co/ASLP-lab/WSYue-ASR)。

---

## English

Offline voice input for Android, built on SenseVoice. It registers as the system speech recognition service, so the voice key of keyboards such as TT9 can use it. Audio is processed on the phone and never uploaded.

Forked from [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service) and tuned for low-end keypad phones such as the Qin F21 Pro (MT6761). Changes: Traditional Chinese output with Taiwan wording, spoken punctuation in Chinese and English, the model stays loaded in memory (about 300 MB RAM), instant switching between Chinese and English, normal English casing, a short vibration when recording starts, Traditional Chinese UI, and its own package ID.

On the F21 Pro a sentence takes about 1–2 s after you stop speaking, compared with 8–12 s for whisperIME.

Install the APK from Releases, open the app, download **SenseVoice Small** (239 MB), grant microphone access, and select the app as the system voice input service (or use the adb command above). If another app with a voice input screen (such as whisperIME) is installed, TT9 opens that app instead, so disable it.

Code is Apache-2.0. Models are downloaded by the app and are not included; SenseVoice Small is under the FunASR Model License.
