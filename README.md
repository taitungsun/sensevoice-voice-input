# sensevoice-voice-input

離線語音轉文字：辨識全程在手機上完成，語音與文字都不會外傳；只有第一次下載模型時需要網路。

安裝後即成為 Android 的系統語音辨識服務，在 TT9 等輸入法按下語音鍵就能使用。

本專案改編自 [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service)，針對 Qin F21 Pro 等效能較低的按鍵手機最佳化，也適用於 Android 8.0 以上、arm64 架構的手機。

## 與原專案的差異

- 輸出繁體中文，你說什麼詞就打出什麼詞，不會自動改掉用詞
- 口述標點：說「逗號」「句號」「問號」「驚嘆號」「冒號」「分號」「頓號」「刪節號」「換行」會轉成標點符號；英文可說 comma、period、full stop、question mark、exclamation mark、colon、semicolon、new line
- 不在每句結尾自動加句號（SenseVoice 原本會加），需要時說「句號」即可
- 模型常駐記憶體：載入一次約需 9 秒（F21 Pro），之後每句不必重新載入。常駐約佔 300 MB 記憶體，待機時不耗 CPU，通知列會顯示「離線語音輸入已就緒」
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
| 麥克風 | 只在輸入法呼叫語音輸入時錄音 |
| 網路 | 只用於下載模型；下載完成後可以關閉 App 的網路權限，不影響使用 |
| 開機啟動、前景服務、通知 | 讓模型常駐記憶體，說完後能快速出字（通知列顯示「離線語音輸入已就緒」） |
| 震動 | 開始錄音時短震動 |
| 查詢已安裝的 App（QUERY_ALL_PACKAGES） | Android 要求辨識服務能查詢到呼叫它的輸入法，否則輸入法呼叫時會被拒絕。App 本身只查詢系統上有哪些語音辨識服務、是否安裝 Shizuku（用於狀態顯示與一鍵設定），不記錄也不傳送 |

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

語音模型不包含在本專案與 APK 中，由 App 從 HuggingFace 下載。SenseVoice Small 由 FunAudioLLM（阿里巴巴集團）釋出，採用 [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)；粵語微調版來自 [ASLP-lab/WSYue-ASR](https://huggingface.co/ASLP-lab/WSYue-ASR)。

---

## English

Offline voice input for Android, built on SenseVoice. It registers as the system speech recognition service, so the voice key of keyboards such as TT9 can use it. Audio is processed on the phone and never uploaded.

Forked from [Soodok/dsh-asr-service](https://github.com/Soodok/dsh-asr-service) and tuned for low-end keypad phones such as the Qin F21 Pro (MT6761). Changes: Traditional Chinese output with Taiwan character forms (words are not replaced), spoken punctuation in Chinese and English, no automatic full stop at the end of each sentence, the model stays loaded in memory (about 300 MB RAM), instant switching between Chinese and English, normal English casing, a short vibration when recording starts, Traditional Chinese UI, and its own package ID.

On the F21 Pro a sentence takes about 1–2 s after you stop speaking, compared with 8–12 s for whisperIME.

Install the APK from Releases, open the app, download **SenseVoice Small** (239 MB), grant microphone access, and select the app as the system voice input service (or use the adb command above). If another app with a voice input screen (such as whisperIME) is installed, TT9 opens that app instead, so disable it.

Privacy: recognition runs entirely on the phone; no audio, text or usage data leaves it, and there are no ads or analytics. Network access is used only to download the model and can be turned off afterwards. QUERY_ALL_PACKAGES is needed so that keyboards calling the recognition service are not rejected by Android; the app itself only looks up the installed speech recognition services and whether Shizuku is installed. Keep background running allowed, or the model is unloaded and every utterance waits about 9 s. Release APKs are signed with certificate SHA-256 `bff094b62c5f55c511fa5fafc07733544aedb3054c4c8f6133b10563408696db`.

Code is Apache-2.0. Models are downloaded by the app and are not included; SenseVoice Small is under the FunASR Model License.
