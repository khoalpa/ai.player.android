# AI Player for Android

Trình phát video cục bộ có thể tạo transcript từ track âm thanh, dịch trên thiết bị và đọc phụ đề đồng bộ với video.

## Sử dụng

1. Chọn **Mở video** để phát tệp bằng Media3.
2. Đặt ngôn ngữ lời thoại trong **Ngôn ngữ và âm lượng**. Vosk hỗ trợ `ja`, `en`, `vi`, `ko`, `zh`. Mặc định là `ja` sang `vi`.
3. Chọn **Tạo transcript** và chọn track âm thanh nếu video có nhiều track. Hoặc chọn **Nhập SRT/VTT** để dùng phụ đề có mốc thời gian.
4. Chọn **Dịch** để dịch từng đoạn bằng ML Kit. Lần đầu có thể cần kết nối để tải model nhận dạng hoặc dịch.
5. Chọn **Tắt**, **Gốc** hoặc **Bản dịch** riêng cho phụ đề và giọng đọc. Thiết bị cần giọng TextToSpeech tương ứng. Nếu lời đọc dài hơn đoạn phụ đề, ứng dụng dừng phần còn lại để theo kịp video.
6. Mở **Xem transcript** để xem các đoạn và chạm vào một đoạn để tua tới đó.

Luồng hiện tại nhận video cục bộ và phụ đề SRT/VTT. Ứng dụng dùng quyền `INTERNET` để tải model. Xem `app/src/main/assets/README-vosk-models.txt` để đóng gói model Vosk vào APK.

## Build và kiểm thử

Cần Android SDK 35, JDK tương thích với cấu hình Gradle của repo và thiết bị hoặc máy ảo Android API 26 trở lên.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK debug nằm trong `app/build/outputs/apk/debug/`. Trên thiết bị, kiểm tra video có một, nhiều hoặc không có track âm thanh, nhập SRT/VTT, tải model lần đầu, dịch offline sau khi tải model, đổi video khi tác vụ đang chạy và tua/tạm dừng khi giọng đọc đang phát.

## Mã nguồn

| Thành phần | Trách nhiệm |
|---|---|
| `MainActivity`, Media3 | Giao diện, điều khiển video và cung cấp vị trí phát |
| `VideoSession` | Track âm thanh, transcript, tác vụ dịch và hủy kết quả cũ |
| `OriginalAudioCaptionGenerator`, `VoskModelStore` | Giải mã track và nhận dạng lời nói |
| `TranscriptParser`, `TranslationClient` | Nhập phụ đề và dịch từng đoạn |
| `CueTimeline`, `CueSpeech` | Chọn đoạn theo vị trí phát và điều khiển giọng đọc |
