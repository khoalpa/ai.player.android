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
| `VideoWork`, `VideoWorkService` | Giữ tác vụ ngoài Activity, thông báo nền, hủy và checkpoint bản dịch |

## Tác vụ nền và khôi phục phiên

- Định danh video được tính một lần khi mở rồi dùng lại cho cache trong phiên. Khi chọn/mở lại video, ứng dụng xác minh lại định danh để phát hiện thay đổi nội dung; không hỗ trợ thay nội dung tệp trong lúc tác vụ đang đọc chính tệp đó.
- Truy vấn tên, metadata và hash chạy ở luồng nền. Đổi lựa chọn video hủy yêu cầu trước, gửi tín hiệu hủy tới provider và đóng luồng đọc đang mở. Provider cần hỗ trợ hủy/đóng luồng để ngắt được thao tác I/O đang bị chặn.
- Nhận dạng/dịch tiếp tục khi rời màn hình hoặc Activity bị tạo lại trong cùng tiến trình. Thông báo xử lý có nút **Hủy** và mở lại ứng dụng; Android 13+ hỏi quyền thông báo ở tác vụ đầu tiên. Từ chối quyền không chặn xử lý nhưng thông báo có thể chỉ xuất hiện trong trình quản lý tác vụ hệ thống.
- Dịch ghi nhận kết quả theo từng đoạn, checkpoint tối đa khoảng mỗi 2 giây khi có tiến độ, và lưu ngay khi hoàn tất/hủy/rời Activity. Sau khi tiến trình bị đóng, mở lại video rồi chọn **Dịch lại** để xử lý các đoạn thiếu. Một lần đổi ngôn ngữ chỉ xóa bản dịch cũ khi có kết quả mới đầu tiên, tránh trộn hai ngôn ngữ.
- Transcript/bản dịch được lưu trong tệp JSON riêng bằng `AtomicFile`; preferences chỉ giữ metadata/vị trí. Dữ liệu phiên cũ trong preferences vẫn đọc được và được chuyển sang tệp ở lần lưu tiếp theo.
- Chưa lưu trạng thái nội bộ recognizer Vosk: nếu tiến trình bị đóng/force-stop trong lúc nhận dạng, cần tạo lại transcript từ đầu; transcript hoàn chỉnh trước đó vẫn được giữ. Foreground service giảm nguy cơ gián đoạn nhưng không bảo đảm sống qua force-stop hoặc khởi động lại thiết bị.
- Android 15+ dùng foreground service loại `mediaProcessing`, các phiên bản trước dùng `dataSync`. Có xử lý timeout hệ thống và giới hạn mỗi phiên dịch vụ tối đa 6 giờ; khi hết hạn, ứng dụng dừng tác vụ và giữ bản dịch đã ghi nhận. Khóa CPU được giải phóng khi dịch vụ dừng.

### Kiểm tra trên thiết bị sau thay đổi

1. Mở video lớn từ provider thiếu metadata; bắt đầu tạo transcript và kiểm tra không đọc/hash lại toàn bộ video. Mở lại sau khi thay nội dung cùng URI phải bỏ phiên cũ.
2. Đang hash hoặc nhập phụ đề từ provider chậm: chọn video khác/hủy, xác nhận kết quả cũ không cập nhật phiên mới và yêu cầu mới không phải đợi đọc hết tệp cũ.
3. Đang nhận dạng/dịch: bấm Home, tắt màn hình, quay lại hoặc tạo lại Activity. Tiến độ tiếp tục, video giữ trạng thái tạm dừng; nút Hủy trên giao diện và thông báo đều kết thúc dịch vụ.
4. Dịch một phần, hủy hoặc đóng tiến trình sau khi checkpoint được ghi. Mở lại và dịch tiếp: các đoạn đã dịch còn nguyên; đổi ngôn ngữ rồi hủy giữa chừng không để lẫn bản dịch ngôn ngữ cũ.
5. Nâng cấp từ dữ liệu phiên cũ: kiểm tra vị trí, transcript, ngôn ngữ và bản dịch sau lần lưu/mở lại. Với tệp phiên ghi lỗi, kiểm tra bản ghi nguyên tử trước còn đọc được.
6. Android 13+: thử chấp nhận/từ chối thông báo. Android 15+: kiểm tra timeout dịch vụ, thông báo biến mất và không giữ khóa CPU sau hủy/hoàn tất.

## Kiểm thử hồi quy tải model và PCM

- Video từ provider không cung cấp kích thước/ngày sửa: mở lại phải giữ vị trí, transcript và bản dịch. Trường hợp này ứng dụng đọc toàn bộ tệp ở luồng nền để tính SHA-256; video lớn hoặc trên cloud có thể mất thêm thời gian. Thay nội dung ở cùng URI phải vô hiệu hóa phiên cũ.
- Model có `conf/model.conf` nhưng hỏng dữ liệu: tạo transcript phải thử tải lại một lần. Nếu model mới vẫn không nạp được, báo lỗi và cho phép thử lại; không lặp tải vô hạn. Kiểm tra hủy trong lúc phục hồi.
- Video có track bắt đầu trễ hoặc timestamp gián đoạn: kiểm tra cả phụ đề xem trước và kết quả cuối sau khoảng gián đoạn. Thời gian từ Vosk được ánh xạ theo timestamp PCM decoder.
- Khi dịch một số đoạn thất bại: chế độ Bản dịch không hiển thị bản gốc như bản dịch. Danh sách ghi rõ đoạn thiếu kèm bản gốc, nút chuyển ngôn ngữ hiển thị số đoạn thiếu; chọn Dịch lại để hoàn tất.
- `AudioTimestampMapTest` kiểm tra offset, khoảng trống, timestamp lùi và sai số tích lũy; `ContentFingerprintTest` kiểm tra thay đổi cuối tệp và ngắt tính hash; `CueTimelineTest` kiểm tra đoạn thiếu bản dịch.

- Khi chưa có model: bắt đầu tạo transcript, hủy trong lúc tải, rồi tạo lại hoặc mở video khác. Kết quả tác vụ cũ không được cập nhật vào phiên mới; tệp giải nén tạm được dọn dẹp.
- Kiểm tra tải model khi mất mạng, tải lại sau khi hủy, và khi máy chủ không cung cấp Content-Length. Phần trăm dựa trên byte ZIP tải về; sau tải chuyển sang trạng thái nạp model.
- Thử video AAC/HE-AAC có sample rate đầu ra decoder khác metadata đầu vào, cùng video mono/stereo. Nhận dạng phải dùng sample rate PCM thực tế. Nếu sample rate thay đổi giữa luồng, tác vụ báo lỗi thay vì trả transcript sai thời gian.
- Kiểm tra tạo transcript không có lời nói và hủy trong lúc nhận dạng; transcript hoàn chỉnh trước đó phải được giữ lại.
- Unit test `ModelArchiveTest` kiểm tra đường dẫn ZIP thoát thư mục và thư mục cùng tiền tố. `ModelDownloadStreamTest` kiểm tra hủy đọc, Content-Length không xác định và đếm byte nén.
