# AI Player Android

Ứng dụng Android native tham chiếu từ bản Windows tại `D:\project\ai.player`.

## Tính năng hiện có

- Mở video cục bộ bằng bộ chọn tệp Android.
- Mở URL video `http://`, `https://`, `rtsp://`, `rtmp://`.
- Mở link YouTube bằng trang mobile trong WebView, kèm nút mở bằng YouTube/browser khi video không cho phát trong WebView.
- Tự lấy caption YouTube nếu video có caption công khai, rồi dùng caption đó cho phụ đề và đọc/lồng tiếng.
- Nhận giọng nói từ micro để tạo transcript live khi video không có caption.
- Tạo caption từ track âm gốc của video cục bộ bằng Vosk offline nếu model ASR đã được đóng gói trong assets.
- Phát/tạm dừng/dừng, tua timeline, chỉnh âm lượng nguồn.
- Nạp transcript `.srt`, `.vtt`, `.txt` và hiển thị phụ đề theo thời gian.
- Chọn nguồn/đích ngôn ngữ, bật/tắt phụ đề nguồn hoặc đích.
- Đọc/lồng tiếng bằng Android TextToSpeech.
- Dịch offline trực tiếp trên điện thoại bằng ML Kit Translation.

## Build

Mở thư mục này bằng Android Studio rồi Sync/Run. Máy hiện tại chưa có Java, Gradle hoặc Android SDK trong PATH nên chưa thể build trực tiếp từ dòng lệnh trong phiên này.

Không cần endpoint dịch, API key hoặc server. Lần đầu dùng một cặp ngôn ngữ, ML Kit cần tải model dịch về thiết bị; sau khi model đã có trên máy, quá trình dịch chạy offline.

Link YouTube là trang web chứ không phải URL media trực tiếp, nên app phát bằng trang mobile trong WebView và có nút mở bằng YouTube/browser nếu video chặn phát trong WebView. App sẽ cố lấy caption YouTube công khai để tạo transcript. Nếu video không có caption hoặc YouTube chặn truy cập caption, hãy nạp transcript `.srt/.vtt/.txt` thủ công. Muốn đọc/lồng tiếng Việt thì cần dịch transcript offline trước.

Trên Android, app thường không thể lấy trực tiếp âm thanh hệ thống từ YouTube/WebView như bản Windows. Khi không lấy được caption, dùng nút `Micro` và phát âm thanh qua loa để tạo transcript live. Micro dùng Vosk offline nên không phụ thuộc SpeechRecognizer của thiết bị, nhưng vẫn cần model Vosk tương ứng trong assets.

Nút `Caption âm gốc` dùng Vosk offline để nhận dạng track âm thanh của video cục bộ. Trước khi build, đặt model Vosk vào `app/src/main/assets/` theo tên `model-ja`, `model-en-us`, `model-vi`, `model-ko` hoặc `model-cn`. Nếu chưa có model, app sẽ báo cần thêm model thay vì gọi mạng.

## Ghi chú kiến trúc

Bản Windows dùng Python, FFmpeg, faster-whisper, NLLB, Tesseract và VieNeu-TTS. Các runtime đó quá nặng để bê nguyên sang điện thoại, nên bản Android này tách điểm nối ở `TranslationClient` và dùng Android TextToSpeech cho MVP. Có thể thay `TranslationClient` bằng server AI Player desktop hoặc runtime mobile sau.
