# AI Themes 1.0.0

`themes/` là bản ghim của bộ giao diện dùng chung cho các dự án trong `D:/project`. Nguồn chuẩn được quản lý tại `ai.atelier/themes`; mỗi ứng dụng giữ một bản sao cục bộ để có thể chạy độc lập, không cần truy cập thư mục của dự án khác.

## Cấu hình giao diện

Tệp `project.json` xác định cấu hình mặc định:

- `theme`: bảng màu `neutral`, `player` hoặc `paper`.
- `mode`: chế độ `system`, `light` hoặc `dark`.
- `style`: phong cách `minimal`, `soft` hoặc `editorial`.
- `density`: mật độ `comfortable` hoặc `compact`.

Token màu gốc nằm trong `tokens.json`. Component chỉ nên tham chiếu các biến semantic có tiền tố `--ui-*`. Không áp theme cho màu nội dung của ảnh, video, phụ đề hoặc màu nhận diện nghiệp vụ.

## Hành vi theo nền tảng

- **Web**: nút tròn **Giao diện** nằm ở góc phải dưới. Lựa chọn được lưu riêng cho từng ứng dụng; chế độ `system` tự cập nhật theo hệ điều hành.
- **Streamlit**: nhóm **Giao diện** nằm trong thanh bên và lưu cấu hình theo phiên.
- **Windows/Gradio**: đọc cấu hình mặc định hoặc các biến môi trường `AI_THEME`, `AI_THEME_MODE`, `AI_THEME_STYLE`, `AI_THEME_DENSITY` khi khởi động.
- **Android**: sử dụng bảng màu Neutral sáng/tối theo hệ thống.
- **Telegram**: giữ bộ chọn chế độ giao diện hiện có.

## Tích hợp vào ứng dụng web

Sao chép `dist/theme.css` và `dist/theme.js` vào thư mục tài nguyên tĩnh của ứng dụng. Nạp JavaScript trong `<head>` trước nội dung trang để khôi phục lựa chọn trước khi vẽ giao diện; nạp stylesheet sau CSS riêng của ứng dụng.

```html
<html data-theme="neutral" data-mode="system" data-style="minimal"
      data-density="comfortable" data-theme-app="my-project">
  <head>
    <script src="/ai-theme.js"></script>
    <link rel="stylesheet" href="/ai-theme.css">
  </head>
  <body class="ui-scope">
    <button class="ui-button ui-primary">Lưu</button>
  </body>
</html>
```

Các class component có sẵn gồm `.ui-input`, `.ui-select`, `.ui-textarea`, `.ui-card`, `.ui-table`, `.ui-feedback`, `.ui-tabs` và `.ui-actions`. Ứng dụng vẫn chịu trách nhiệm triển khai điều hướng bàn phím và trạng thái ARIA cho tab.

Nút đổi giao diện sử dụng dialog gốc của trình duyệt. Font mặc định là font hệ thống có hỗ trợ tiếng Việt. Phong cách `editorial` dùng serif cho tiêu đề; khoảng cách và bán kính bo góc được điều khiển độc lập với bảng màu.

## Phát triển tại nguồn chuẩn

Chạy các lệnh sau từ dự án AI Atelier:

```powershell
.venv-ai/Scripts/python.exe themes/tools/build.py
.venv-ai/Scripts/python.exe themes/tools/deploy.py
.venv-ai/Scripts/python.exe themes/tools/verify.py
node themes/tools/theme.test.js
```

- `build.py` sinh CSS từ token.
- `deploy.py` đồng bộ bản ghim và tài nguyên runtime, đồng thời cập nhật các điểm nạp đã biết.
- `verify.py` kiểm tra kết quả triển khai.
- `theme.test.js` chạy bộ kiểm thử JavaScript.

Khi triển khai, tệp nguồn bị thay đổi được sao lưu trong `.theme-backups/` của từng dự án. Manifest tại `themes/deployment/` lưu đường dẫn và checksum; chạy lại sẽ bỏ qua các tệp đã có nội dung giống nhau.

Hoàn tác một lần triển khai bằng lệnh:

```powershell
.venv-ai/Scripts/python.exe themes/tools/deploy.py --rollback themes/deployment/TIMESTAMP.json
```

Quá trình hoàn tác sẽ dừng nếu tệp đã tiếp tục được chỉnh sửa sau lần triển khai, nhờ đó không ghi đè thay đổi mới của người dùng. Các gói build cũ hoặc tiến trình đang chạy cần được build lại hay khởi động lại để nhận tài nguyên mới.

Manifest của đợt triển khai đầu tiên nằm tại `deployment/initial-rollout.json`. Kết quả xác minh được lưu trong `verification/results.json`, còn ảnh trang mẫu nằm tại `verification/preview.png`.

## Điểm tích hợp

| Dự án | Cách tích hợp |
|---|---|
| `ai.atelier` | Web, ánh xạ token và palette cũ |
| `ai.studio` | Web, ánh xạ biến và danh sách tài nguyên máy chủ |
| `watermarks-remover` | Web, ánh xạ biến và tuyến tài nguyên |
| `ai.slide`, `ai.story` | Studio và cửa sổ Audio/Video Streamlit |
| `ai.player` | QSS; giữ bố cục, thay bảng màu cửa sổ chính |
| `ai.meeting`, `ai.player.android` | Resource màu; giữ ID cũ và `values-night` |
| `ai.telegram.android` | Material 3 `ColorScheme`; giữ bộ chọn sáng/tối |
| `VieNeu-TTS` | Gradio CPU/CUDA/XPU và giao diện streaming FastAPI |
| `sd.webui` | Extension `ai-shared-themes`; không sửa mã upstream |

CSS cung cấp đầy đủ component cho web. Adapter native chỉ ánh xạ những khả năng phù hợp với từng nền tảng; Android hiện chưa có bộ chọn ba phong cách và hai mức mật độ. Khi thêm màn hình hoặc component mới, hãy dùng semantic token thay vì mở rộng lớp tương thích màu cũ.
