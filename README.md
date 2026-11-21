# AI Player for Android

AI Player phát video bằng Media3, tạo transcript từ lời thoại bằng Vosk, dịch trên thiết bị bằng ML Kit và phát âm phụ đề bằng TextToSpeech của Android.

## Cài đặt từ GitHub

Cần thiết bị chạy Android 8.0 (API 26) trở lên.

### Cài bản standalone release đã ký

1. Mở [GitHub Releases](https://github.com/khoalpa/ai.player.android/releases), chọn bản phát hành và tải `app-standalone-release.apk` trong mục **Assets**. Nếu chưa có APK trên Releases và bạn đã build bản đã ký bằng Android Studio, dùng tệp `app/standalone/release/app-standalone-release.apk` trong dự án hoặc đường dẫn xuất đã chọn khi build.
2. Nếu tải trên máy tính, chép APK sang điện thoại. Mở tệp APK bằng trình quản lý tệp hoặc từ mục tải xuống của trình duyệt.
3. Khi Android hỏi quyền, bật **Cho phép từ nguồn này** cho ứng dụng đang mở APK. Quay lại màn hình cài đặt, chọn **Cài đặt**, rồi chọn **Mở** để chạy AI Player.

Để cài từ máy tính qua ADB, bật **Gỡ lỗi USB** trên điện thoại, kết nối cáp USB và chấp nhận yêu cầu xác thực. Mở PowerShell tại thư mục chứa APK rồi chạy:

```powershell
adb devices
adb install -r .\app-standalone-release.apk
```

Kiểm tra thiết bị hiện trạng thái `device` và lệnh cài trả về `Success`. Khi cập nhật tại chỗ, APK cần có chữ ký tương thích với bản đang cài và mã phiên bản bằng hoặc cao hơn. Nếu bản đang cài dùng khóa debug, xem cách xử lý lỗi chữ ký bên dưới trước khi gỡ ứng dụng.

### APK debug từ GitHub Actions

Nếu chưa có bản standalone trên Releases hoặc cần thử một commit cụ thể, dùng APK debug từ GitHub Actions.

1. Đăng nhập GitHub và mở workflow [Android build](https://github.com/khoalpa/ai.player.android/actions/workflows/android-build.yml).
2. Chọn lần chạy thành công mới nhất của nhánh `main` hoặc lần chạy ứng với commit bạn muốn thử. Ở cuối trang **Summary**, tải artifact **ai-player-debug-apk** trong mục **Artifacts**. Artifact được giữ 14 ngày. Nếu đã hết hạn, chọn lần chạy thành công còn artifact hoặc tự build theo hướng dẫn bên dưới.
3. Giải nén tệp ZIP và lấy `app-standalone-debug.apk`. Đây là bản debug cài trực tiếp để dùng thử.
4. Chuyển APK sang điện thoại nếu tải trên máy tính. Mở APK trên điện thoại, cấp quyền **Cài đặt ứng dụng không rõ nguồn gốc** cho trình duyệt hoặc trình quản lý tệp đang mở APK khi Android yêu cầu, rồi chọn **Cài đặt**.

Nếu dùng ADB, bật **Gỡ lỗi USB**, kết nối điện thoại và chạy lệnh sau từ thư mục chứa APK.

```powershell
adb devices
adb install -r .\app-standalone-debug.apk
```

Nếu gặp `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, APK đang cài và APK mới có chữ ký khác nhau. Dùng APK có cùng chữ ký để cập nhật và giữ dữ liệu. Nếu cần gỡ bản cũ trước khi cài, hãy lưu lại dữ liệu cần giữ vì thao tác gỡ sẽ xóa dữ liệu riêng của ứng dụng.

## Mở và lưu video

- **Mở video → Chọn tệp trên thiết bị** để phát tệp cục bộ. Màn hình khởi động hiển thị tối đa ba video gần đây và cho phép mở lại tại vị trí đã lưu.
- **Mở video → Tải video từ URL** để tải video từ liên kết được hỗ trợ. Video tải thành công xuất hiện trong **Video đã lưu**.
- Có thể chia sẻ tệp video từ Telegram sang AI Player bằng menu chia sẻ Android. Với liên kết bài đăng Telegram, ứng dụng có thể thử tải từ bài đăng công khai hoặc dùng tài khoản Telegram qua TDLib. Khả năng tải phụ thuộc vào quyền truy cập và quyền lưu của bài đăng. Xem [luồng nhập Telegram](docs/telegram-import.md) và [tích hợp TDLib](docs/telegram-tdlib-integration.md).
- Có thể mở liên kết `t.me`, `www.t.me` và `telegram.me` qua AI Player khi Android chuyển liên kết cho ứng dụng. Nếu thiết bị mở bằng Telegram hoặc trình duyệt theo mặc định, có thể dùng **Chia sẻ → AI Player** hoặc dán liên kết vào **Mở Telegram**.

## Transcript, bản dịch và phụ đề

1. Vào **Cài đặt → Ngôn ngữ và phụ đề** để chọn ngôn ngữ nguồn và đích. Mặc định là tiếng Nhật sang tiếng Việt. Vosk hỗ trợ nhận dạng tiếng Nhật, Anh, Việt, Hàn và Trung.
2. Chọn **Tạo transcript** và chọn track âm thanh nếu video có nhiều track. Có thể dùng **Nhập SRT/VTT** khi đã có phụ đề theo thời gian.
3. Chọn **Dịch**. Trong **Cài đặt → Quản lý model**, có thể tải trước model nhận dạng và dịch qua Wi-Fi hoặc dữ liệu di động để dùng offline. Tiếng Anh là ngôn ngữ trung gian của ML Kit và không cần tải gói dịch riêng.
4. Nếu bản dịch còn thiếu, chọn **Dịch lại N đoạn thiếu**. Khi cần xử lý từng đoạn, chọn **Xử lý N đoạn thiếu**, mở đoạn theo mốc thời gian, nghe lại hoặc chỉnh lời gốc và ngôn ngữ nguồn, rồi dịch đoạn đó. Các đoạn đã dịch được giữ lại.
5. Chọn **Tắt**, **Gốc** hoặc **Bản dịch** riêng cho **Phụ đề** và **Phát âm**. Phát âm cần giọng TextToSpeech tương ứng trên thiết bị. Có thể chạm vào một đoạn trong danh sách transcript để tua đến mốc đó.

Có thể đóng gói model nhận dạng Vosk vào APK theo [hướng dẫn model Vosk](app/src/main/assets/README-vosk-models.txt).

## Build và kiểm thử

Để tự build từ GitHub, tải mã nguồn bằng Git.

```powershell
git clone https://github.com/khoalpa/ai.player.android.git
cd ai.player.android
```

Cần JDK 21, Android SDK 36 và thiết bị hoặc máy ảo Android 8.0 (API 26) trở lên. Trong Android Studio, chọn JDK 21 cho Gradle, mở thư mục gốc dự án, chọn `standaloneDebug` hoặc `playDebug` trong **Build Variants**, rồi chọn thiết bị và bấm **Run**.

| Biến thể | Mục đích |
|---|---|
| `standalone` | Cài đặt riêng bằng APK |
| `play` | Bản build dành cho Google Play |

Để kiểm tra đăng nhập Telegram qua TDLib trên thiết bị `arm64-v8a`, cần đóng gói thư viện native tương ứng. Trên Linux hoặc WSL2 có Docker, chạy `bash scripts/build-tdlib-jsonjava.sh` trước khi build. Xem [hướng dẫn TDLib](docs/telegram-tdlib-integration.md).

### Kiểm tra Telegram trên thiết bị ARM64

1. Mở liên kết bài công khai từ bên ngoài khi AI Player đang đóng và đang chạy. Kiểm tra màn **Nội dung Telegram**, rồi thử **Mở trong Telegram** để xác nhận trình chọn ứng dụng không đưa liên kết ngược về AI Player.
2. Đăng nhập Telegram, mở bài riêng tư có quyền truy cập và thử chuyển video của bài sang trình phát chính. Kiểm tra cả trường hợp thiếu quyền truy cập.
3. Mở bài dài, mở rộng nội dung, bật bản dịch, cuộn xuống rồi cho Android tạo lại Activity. Kiểm tra ứng dụng mở lại bài, hiển thị bản dịch đã lưu và cuộn về vị trí cũ sau khi tải nội dung.

Chạy từ thư mục gốc trên Windows. Trên Linux hoặc macOS, thay `.\gradlew.bat` bằng `./gradlew`.

```powershell
.\gradlew.bat :app:assembleStandaloneDebug :app:testStandaloneDebugUnitTest :app:lintStandaloneDebug
.\gradlew.bat :app:assemblePlayDebug :app:testPlayDebugUnitTest :app:lintPlayDebug
```

APK debug nằm trong `app/build/outputs/apk/<flavor>/debug/`. Trên thiết bị, kiểm tra video có một, nhiều hoặc không có track âm thanh, nhập SRT/VTT, tải model lần đầu, dịch offline sau khi tải model, đổi video khi tác vụ đang chạy và tua/tạm dừng khi giọng đọc đang phát.

### Chuẩn bị bản phát hành

Hai biến thể dùng chung mã nguồn, package, phiên bản và cấu hình SDK. Chạy workflow **Android build** thủ công từ commit đã chốt để tạo cặp AAB và APK chưa ký, hoặc chạy lệnh sau trên cùng checkout:

```powershell
.\gradlew.bat :app:bundlePlayRelease :app:assembleStandaloneRelease
```

| Biến thể | Gói phát hành | Khóa ký |
|---|---|---|
| `playRelease` | `app/build/outputs/bundle/playRelease/app-play-release.aab` | Upload key đăng ký trong Play Console. Google Play ký APK phân phối bằng app signing key. |
| `standaloneRelease` | `app/build/outputs/apk/standalone/release/app-standalone-release-unsigned.apk` | Khóa phát hành APK cài trực tiếp. Giữ khóa tương thích với APK đã phân phối. |

Dùng **Build → Generate Signed Bundle / APK** trong Android Studio để tạo bản đã ký từ cùng commit. Kiểm tra `versionCode` với lịch sử phát hành trước khi tải lên. Chuyển kênh tại chỗ cần chữ ký tương thích và `versionCode` phù hợp. So sánh chứng thư của APK phân phối qua Play với APK cài trực tiếp và thử cập nhật trên thiết bị có dữ liệu trước khi công bố khả năng chuyển kênh.

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
- Dịch ghi nhận kết quả theo từng đoạn, checkpoint tối đa khoảng mỗi 2 giây khi có tiến độ, và lưu ngay khi hoàn tất/hủy/rời Activity. Sau khi tiến trình bị đóng, mở lại video rồi dùng tùy chọn dịch các đoạn thiếu để tiếp tục. Một lần đổi ngôn ngữ chỉ xóa bản dịch cũ khi có kết quả mới đầu tiên, tránh trộn hai ngôn ngữ.
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
- Khi dịch một số đoạn thất bại: chế độ Bản dịch không hiển thị bản gốc như bản dịch. Danh sách ghi rõ đoạn thiếu kèm bản gốc, nút chuyển ngôn ngữ hiển thị số đoạn thiếu; dùng tùy chọn dịch lại hoặc xử lý từng đoạn còn thiếu.
- `AudioTimestampMapTest` kiểm tra offset, khoảng trống, timestamp lùi và sai số tích lũy; `ContentFingerprintTest` kiểm tra thay đổi cuối tệp và ngắt tính hash; `CueTimelineTest` kiểm tra đoạn thiếu bản dịch.

- Khi chưa có model: bắt đầu tạo transcript, hủy trong lúc tải, rồi tạo lại hoặc mở video khác. Kết quả tác vụ cũ không được cập nhật vào phiên mới; tệp giải nén tạm được dọn dẹp.
- Kiểm tra tải model khi mất mạng, tải lại sau khi hủy, và khi máy chủ không cung cấp Content-Length. Phần trăm dựa trên byte ZIP tải về; sau tải chuyển sang trạng thái nạp model.
- Thử video AAC/HE-AAC có sample rate đầu ra decoder khác metadata đầu vào, cùng video mono/stereo. Nhận dạng phải dùng sample rate PCM thực tế. Nếu sample rate thay đổi giữa luồng, tác vụ báo lỗi thay vì trả transcript sai thời gian.
- Kiểm tra tạo transcript không có lời nói và hủy trong lúc nhận dạng; transcript hoàn chỉnh trước đó phải được giữ lại.
- Unit test `ModelArchiveTest` kiểm tra đường dẫn ZIP thoát thư mục và thư mục cùng tiền tố. `ModelDownloadStreamTest` kiểm tra hủy đọc, Content-Length không xác định và đếm byte nén.

