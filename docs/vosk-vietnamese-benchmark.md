# Đo đối chứng Vosk tiếng Việt trên Android

So sánh `vosk-model-small-vn-0.4` (đang dùng) và `vosk-model-vn-0.4` trên cùng Redmi Turbo 4 Pro. Benchmark nằm trong APK kiểm thử và chạy bằng mã Vosk Android của ứng dụng.

## Chuẩn bị

- Tải hai gói từ [danh mục Vosk](https://alphacephei.com/vosk/models), giải nén thành hai thư mục đúng tên model.
- Chuẩn bị clip lời thoại có bản chép tay, gồm giọng Bắc, Trung, Nam và các mức tiếng nền. Chuyển tất cả clip sang PCM raw mono, 16 kHz, signed 16 bit little-endian. Dùng tên như `bac_yen_tinh_01.pcm`.
- Trong thư mục `vosk-benchmark/corpus`, đặt các tệp PCM và `references.tsv` mã hóa UTF-8. Mỗi dòng gồm tên tệp, một ký tự tab và lời thoại chuẩn. Hai thư mục model nằm ngay dưới `vosk-benchmark`.
- Chạy khi chỉ có một thiết bị ADB. Dùng bản Debug đã cài cùng biến thể với APK kiểm thử. Cài đè bản phát hành bằng bản Debug có thể bị Android chặn do chữ ký khác.

## Chạy trên thiết bị

```powershell
.\gradlew.bat :app:assembleStandaloneDebug :app:assembleStandaloneDebugAndroidTest
adb install -r app/build/outputs/apk/standalone/debug/app-standalone-debug.apk
adb install -r app/build/outputs/apk/androidTest/standalone/debug/app-standalone-debug-androidTest.apk

adb push vosk-benchmark /data/local/tmp/
adb shell "run-as com.aiplayer.android mkdir -p files/vosk-benchmark"
adb shell "tar -C /data/local/tmp/vosk-benchmark -cf - . | run-as com.aiplayer.android tar -C files/vosk-benchmark -xf -"

$runner = 'com.aiplayer.android.test/com.aiplayer.android.VietnameseModelBenchmark'
adb shell am instrument -w -e model vosk-model-small-vn-0.4 $runner
adb shell am instrument -w -e model vosk-model-vn-0.4 $runner
```

Khi chưa có bản chép chuẩn, thêm `-e timingOnly true` vào mỗi lệnh `am instrument` để đo tốc độ, bộ nhớ và lấy transcript. Chế độ này bỏ trường WER. Nếu hai video có PCM trùng nhau, chỉ đưa một bản vào corpus để tránh tính cùng lời thoại hai lần.

Giữ cùng tập clip và điều kiện thiết bị, chạy ít nhất ba vòng cho mỗi model và luân phiên thứ tự model giữa các vòng. Lưu kết quả JSON từng lượt. So sánh WER tổng và theo nhóm clip, thời gian nạp, hệ số thời gian thực và PSS lấy mẫu sau khi nạp và cuối mỗi clip. WER ở đây dùng đơn vị phân tách bằng khoảng trắng, giữ dấu tiếng Việt và bỏ dấu câu. PSS lấy mẫu chỉ phản ánh các thời điểm đo.

Dựa trên kết quả thực tế để quyết định có bổ sung model lớn vào bộ chọn. Benchmark này không thay đổi model mặc định của ứng dụng.
