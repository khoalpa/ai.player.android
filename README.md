# AI Player for Android

AI Player là trình phát video Android có khả năng tạo phụ đề, dịch nội dung và đọc lời thoại ngay trên thiết bị. Dự án được phát triển theo chức năng của bản AI Player dành cho Windows, nhưng sử dụng các thành phần phù hợp với môi trường Android.

## Tính năng

- Phát video cục bộ từ bộ chọn tệp của Android.
- Phát URL media qua `http://`, `https://`, `rtsp://` và `rtmp://` bằng Media3 ExoPlayer.
- Mở trang web và YouTube trong WebView; có thể chuyển sang YouTube hoặc trình duyệt ngoài khi nội dung không phát được trong ứng dụng.
- Tự động lấy caption công khai của YouTube khi có.
- Nạp transcript từ tệp `.srt`, `.vtt` hoặc `.txt`.
- Tạo transcript từ micro hoặc track âm thanh của video bằng Vosk.
- Dịch transcript trên thiết bị bằng ML Kit Translation.
- Hiển thị phụ đề theo thời gian, chọn ngôn ngữ nguồn và ngôn ngữ đích.
- Đọc bản dịch bằng Android TextToSpeech, với âm lượng nguồn và âm lượng lồng tiếng độc lập.
- Hỗ trợ giao diện sáng/tối theo cài đặt hệ thống.

## Yêu cầu

- Android Studio có Android SDK 35.
- JDK tương thích với Android Gradle Plugin 9.2.1.
- Thiết bị hoặc máy ảo chạy Android 8.0 (API 26) trở lên.
- Kết nối mạng cho video trực tuyến và lần tải đầu của model ML Kit hoặc Vosk.

Ứng dụng không cần API key, endpoint dịch hay máy chủ riêng.

## Cài đặt và chạy

1. Clone hoặc mở thư mục dự án trong Android Studio.
2. Chờ Gradle đồng bộ các dependency.
3. Kết nối thiết bị Android hoặc khởi động máy ảo.
4. Chọn cấu hình `app`, sau đó bấm **Run**.

Có thể build từ dòng lệnh tại thư mục gốc:

```powershell
.\gradlew.bat assembleDebug
```

APK debug được tạo trong `app/build/outputs/apk/debug/`.

Chạy unit test bằng lệnh:

```powershell
.\gradlew.bat test
```

## Cách sử dụng

### Phát nội dung

- Chọn video trên thiết bị để phát bằng player native.
- Nhập URL media trực tiếp để phát bằng ExoPlayer.
- Nhập địa chỉ trang web, link YouTube hoặc từ khóa tìm kiếm để dùng chế độ trình duyệt.

Với YouTube, ứng dụng mở trang mobile trong WebView và đồng thời thử tải caption công khai. Một số video có thể chặn phát nhúng; khi đó hãy dùng nút mở ngoài.

### Tạo và dịch phụ đề

Ứng dụng hỗ trợ ba nguồn transcript:

1. Caption công khai của YouTube.
2. Tệp `.srt`, `.vtt` hoặc `.txt` do người dùng chọn.
3. Nhận dạng giọng nói bằng Vosk từ micro hoặc âm thanh video.

Sau khi có transcript, chọn ngôn ngữ nguồn và ngôn ngữ đích rồi chạy dịch. ML Kit tải model ngôn ngữ trong lần đầu sử dụng; các lần dịch sau có thể chạy offline khi model đã có trên thiết bị.

Tệp văn bản thuần không có mốc thời gian sẽ được chia thành từng câu và gán thời lượng ước tính. Để phụ đề khớp chính xác với video, nên dùng SRT hoặc WebVTT.

### Nhận dạng giọng nói với Vosk

Vosk được dùng cho hai chế độ:

- **Micro**: nghe âm thanh qua microphone và tạo transcript trực tiếp.
- **Audio caption**: nhận dạng track âm thanh của video đang phát bằng player native.

Ứng dụng ưu tiên model được đóng gói trong `app/src/main/assets/`. Nếu không tìm thấy, ứng dụng tự tải model nhỏ phù hợp trong lần dùng đầu rồi lưu vào bộ nhớ riêng để tái sử dụng. Xem hướng dẫn tại `app/src/main/assets/README-vosk-models.txt` nếu muốn đóng gói model cùng APK.

Android thường không cho ứng dụng lấy trực tiếp âm thanh hệ thống từ YouTube hoặc WebView. Nếu video không có caption, hãy phát âm thanh qua loa và dùng chế độ **Micro**, hoặc mở một nguồn media trực tiếp bằng player native.

## Ngôn ngữ Vosk

| Mã nguồn | Model |
|---|---|
| `ja`, `jp` | Tiếng Nhật |
| `vi`, `vn` | Tiếng Việt |
| `ko` | Tiếng Hàn |
| `zh`, `cn` | Tiếng Trung |
| `auto`, `en` hoặc để trống | Tiếng Anh Mỹ |

Cặp ngôn ngữ mặc định của ứng dụng là tiếng Nhật sang tiếng Việt.

## Kiến trúc

| Thành phần | Vai trò |
|---|---|
| Media3 ExoPlayer | Phát video cục bộ và URL media |
| WebView | Mở trang web và trang YouTube mobile |
| Vosk Android | Nhận dạng giọng nói offline |
| ML Kit Translation | Dịch transcript trên thiết bị |
| ML Kit Language ID | Hỗ trợ xác định ngôn ngữ |
| Android TextToSpeech | Đọc hoặc lồng tiếng bản dịch |

Bản Windows sử dụng Python, FFmpeg, faster-whisper, NLLB, Tesseract và VieNeu-TTS. Các runtime này không được đưa nguyên vào ứng dụng Android vì kích thước và yêu cầu tài nguyên. Phiên bản Android thay thế chúng bằng các thư viện native nhẹ hơn và giữ phần dịch tách biệt trong `TranslationClient` để có thể đổi backend sau này.

## Quyền ứng dụng

- `INTERNET`: phát nội dung trực tuyến và tải model lần đầu.
- `RECORD_AUDIO`: nhận dạng giọng nói từ micro.

Quyền micro chỉ được yêu cầu khi người dùng bật tính năng thu âm.

## Cấu trúc dự án

```text
app/
  src/main/java/com/aiplayer/android/   Mã nguồn ứng dụng
  src/main/res/                         Giao diện, màu sắc và biểu tượng
  src/main/assets/                      Model Vosk tùy chọn
themes/                                 Bản ghim của bộ giao diện dùng chung
```

## Giới hạn hiện tại

- Khả năng phát YouTube trong WebView phụ thuộc chính sách nhúng của từng video và trạng thái đăng nhập trên thiết bị.
- Caption YouTube chỉ tải được khi track caption có thể truy cập công khai.
- Chất lượng nhận dạng phụ thuộc ngôn ngữ, model Vosk, chất lượng âm thanh và tiếng ồn môi trường.
- Giọng đọc phụ thuộc engine TextToSpeech và gói ngôn ngữ được cài trên thiết bị.
