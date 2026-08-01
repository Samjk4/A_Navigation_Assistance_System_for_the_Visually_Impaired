# Navigation Assistance System for the Visually Impaired

這是一個以 Android 裝置為基礎的輔助導航系統，目標是透過外接 USB 攝影機與即時物件辨識，協助視障使用者辨識前方環境，並以語音提示的方式提供簡單的導航與環境感知資訊。

## 專案簡介

本專案結合了以下能力：
- 使用外接 UVC 攝影機進行即時影像擷取
- 使用 TensorFlow Lite 與 YOLOv8 模型做物件偵測
- 將偵測結果以視覺方框與語音提示呈現
- 支援交通號誌辨識、障礙物辨識與人物/車輛偵測
- 透過 Android Compose 建構直覺的使用者介面

## 主要功能

- 即時辨識前方物件與障礙物
- 透過 Text-to-Speech 播報偵測結果
- 支援 USB 裝置插入時自動偵測與連線
- 可調整曝光、語音間隔與交通辨識模式
- 提供除錯資訊，方便開發與測試

## 技術架構

- Android Kotlin
- Jetpack Compose
- CameraX
- TensorFlow Lite
- YOLOv8 物件辨識模型
- USB Host / UVC 攝影機支援
- Text-to-Speech（TTS）

## 專案結構

- app/src/main/java/com/example/myapplication2
  - MainActivity.kt：應用程式入口與 USB 插入事件處理
  - YoloCameraScreen.kt：主畫面、相機流程、模型推論與語音播報邏輯
  - yolo/：YOLO 模型封裝與偵測結果處理
- app/src/main/assets
  - 存放模型檔案，例如 yolov8n_float16.tflite 與 traffic_float16.tflite
- app/src/main/AndroidManifest.xml
  - 權限與 USB / 相機相關設定

## 開發環境需求

- Android Studio
- JDK 11 或以上
- Android SDK 36（專案目前設定）
- 一台支援 USB OTG 的 Android 裝置
- 外接 UVC 相容攝影機

## 安裝與執行

1. 下載或複製此專案
2. 開啟專案於 Android Studio
3. 確認模型檔案已放置於 app/src/main/assets
4. 同步 Gradle 設定
5. 連接支援 UVC 的外接攝影機
6. 於裝置上執行應用程式

### 建置指令

Windows：
```powershell
./gradlew.bat assembleDebug
```

macOS / Linux：
```bash
./gradlew assembleDebug
```

## 使用方式

1. 啟動 App 後，系統會請求相機權限
2. 連接外接 USB 攝影機後，App 會嘗試建立 UVC 連線
3. 影像畫面上會即時顯示偵測方框
4. 系統會依據偵測結果進行語音提示
5. 可透過介面調整曝光與語音設定

## 模型說明

本專案目前使用 TensorFlow Lite 格式的 YOLO 模型，主要包含：
- 一般物件辨識模型：yolov8n_float16.tflite
- 交通相關辨識模型：traffic_float16.tflite

模型檔案請放在 app/src/main/assets 目錄下，並確保檔名與程式碼中指定名稱一致。

## 注意事項

- 本專案屬於研究與開發用途，並非醫療設備
- 辨識準確度會受到光線、攝影機品質與環境干擾影響
- 使用外接攝影機前，請確認裝置支援 USB Host 模式與 UVC 相容

## 未來展望

- 加入更精準的障礙物分類與危險程度判斷
- 支援更多語言與更自然的語音提示
- 整合 GPS / 地圖導航與即時路線導引
- 提升辨識速度與低功耗運作
