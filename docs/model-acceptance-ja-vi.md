# Nghiệm thu model nhận dạng và đối chứng Nhật → Việt

## Nghiệm thu Qwen trên Redmi

Thực hiện trên Redmi Turbo 4 Pro cho cả Standalone Debug và Play Debug. Ghi mã build trong **Cài đặt → Thông tin ứng dụng**.

1. Trong **Cài đặt → Quản lý model → Dịch**, bắt đầu tải Qwen. Kiểm tra giao diện hiển thị dung lượng đã tải và tổng dung lượng. Bấm **Hủy tải**, sau đó mở lại màn hình. Kiểm tra dung lượng đã tải vẫn hiển thị và có nút **Tiếp tục tải**.
2. Bấm **Tiếp tục tải** rồi ngắt kết nối mạng khi tác vụ đang chạy. Sau khi ứng dụng báo gián đoạn, kết nối lại và tiếp tục. Kiểm tra tiến độ bắt đầu từ dung lượng đã lưu, hoàn tất tải và trạng thái chuyển sang **Đã tải**. Lặp lại thao tác hủy và tiếp tục từ hộp thoại **Dịch transcript → Ngữ cảnh** nếu chưa cài model.
3. Tải sẵn các model ML Kit cần thử, rồi bật chế độ máy bay. Với cùng một video thử, nhập từng SRT mẫu bên dưới và đặt đúng ngôn ngữ nguồn. Chạy **Nhanh**, xuất SRT tiếng Việt, rồi dịch lại cùng transcript bằng **Ngữ cảnh**. Kiểm tra bước xem trước, bấm **Áp dụng**, xuất SRT thứ hai và đối chiếu mốc thời gian.
4. Ghi thời gian từ lúc bấm **Dịch** đến khi có kết quả. Đo **TOTAL PSS** trước và trong lúc dịch bằng `adb shell dumpsys meminfo com.aiplayer.android`, ghi giá trị cao nhất quan sát được. Lặp lại trên video có transcript dài để đánh giá thời gian, bộ nhớ và khả năng tiếp tục sử dụng trình phát khi tác vụ chạy.

| Nguồn | SRT | Chi tiết cần đối chiếu |
| --- | --- | --- |
| Nhật | [ja-vi-review.srt](../samples/translation/ja-vi-review.srt) | Tên Sato, Tanaka, giá và phí vận chuyển, đổi giờ họp, điều kiện nếu trời mưa |
| Hàn | [ko-vi-context-review.srt](../samples/translation/ko-vi-context-review.srt) | Xưng hô giữa trưởng nhóm và nhân viên, tên Jimin, cụm `발이 넓다`, hai đại lý |
| Anh | [en-vi-context-review.srt](../samples/translation/en-vi-context-review.srt) | Xưng hô giữa giáo viên và học sinh, tên Maya, thành ngữ “break the ice”, thứ Sáu và hai bản in |

Chấm mỗi bản dịch theo ba tiêu chí, mỗi tiêu chí từ 0 đến 2 điểm. Điểm 0 là sai hoặc mất ý, 1 là đúng một phần, 2 là đúng và tự nhiên. Lưu hai SRT để đối chiếu từng đoạn.

| Biến thể | Nguồn | Chế độ | Đúng nghĩa /2 | Xưng hô /2 | Tên và thuật ngữ /2 | Thời gian | PSS trước / cao nhất quan sát được |
| --- | --- | --- | --- | --- | --- | --- | --- |
| | | | | | | | |

## Model nhận dạng trên Redmi

Dùng cùng đoạn video tiếng Việt khoảng 30 giây cho cả hai model. Lặp lại trên Play Debug và Standalone Debug.

| Bước | Kết quả mong đợi |
|---|---|
| Tạo transcript bằng Small | Tùy chọn xử lý hiện Vosk Small và đúng tên gói. |
| Nhận dạng lại bằng Large | Chỉ khi tác vụ thành công, transcript hiện tại được gắn tên gói Large. |
| Đóng và mở lại video | Transcript và nhãn Large được khôi phục. |
| Bắt đầu nhận dạng bằng Small rồi hủy | Transcript và nhãn Large trước đó được khôi phục. |
| Nhập SRT trên video thử riêng | Nhãn hiển thị “Phụ đề đã nhập”. |

Nếu tác vụ dừng tiến triển, ghi lại tên model, dòng trạng thái cuối và logcat trước khi dừng ứng dụng.

## Đối chứng ML Kit và DeepL

Dùng [phụ đề kiểm thử](../samples/translation/ja-vi-review.srt), gồm tám câu soạn riêng cho bài thử. Trong AI Player, nhập SRT và chọn nguồn Nhật, đích Việt. Lưu bản dịch ML Kit theo thứ tự đoạn. Chạy cùng tám đoạn qua DeepL với nguồn JA và đích VI, mỗi đoạn là một mục văn bản riêng. So sánh hai bản theo bảng dưới và dùng cùng giọng tiếng Việt khi nghe.

| Đoạn | Ý cần giữ trong bản dịch | ML Kit | DeepL |
|---|---|---|---|
| 1 | Hỏi Sato đã nhận báo giá hôm qua chưa. | | |
| 2 | Đã nhận, phí vận chuyển chưa được tính. | | |
| 3 | Nhờ Tanaka kiểm tra. | | |
| 4 | Không cần gấp, hạn chót thứ Sáu. | | |
| 5 | Cuộc họp lúc 4 giờ chiều, thay vì 3 giờ. | | |
| 6 | Chỉ in hai bản tài liệu. | | |
| 7 | Nếu trời mưa, người nói sẽ chờ ở lối vào nhà ga. | | |
| 8 | Nếu lạc đường thì gọi, người nói sẽ đến đón. | | |

Ghi điểm 0–2 riêng cho độ đúng nghĩa và độ tự nhiên khi đọc. Đánh dấu lỗi tên riêng, xưng hô, số liệu, phủ định hoặc mất điều kiện. Mở rộng sang clip thực tế và các cặp Hàn → Việt, Anh → Việt nếu kết quả Nhật → Việt cho thấy cải thiện rõ.

### Thu hai bản dịch để chấm

Trong AI Player, xuất **bản dịch SRT** tiếng Việt sau khi đủ tám đoạn. Script ghép bản dịch ML Kit và DeepL theo thứ tự và xác minh mốc thời gian của SRT. DeepL chỉ nhận tám câu mẫu từ tệp kiểm thử. Đặt khóa API trong biến môi trường `DEEPL_AUTH_KEY` của PowerShell. Thêm `-Pro` nếu dùng DeepL API Pro.

```powershell
.\scripts\compare-ja-vi.ps1 -MlKitSrt 'DUONG-DAN-BAN-DICH-ML-KIT.srt' |
    Export-Csv -Path "$env:TEMP\ai-player-ja-vi-comparison.csv" -NoTypeInformation -Encoding UTF8
```
