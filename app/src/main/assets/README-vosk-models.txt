# Model Vosk cho AI Player

Thư mục này dùng để đóng gói model nhận dạng giọng nói Vosk cùng ứng dụng.
Đây là bước tùy chọn: nếu không có model trong assets, AI Player sẽ tải model
nhỏ phù hợp từ alphacephei.com trong lần sử dụng đầu tiên và lưu vào bộ nhớ
riêng của ứng dụng để dùng lại offline.

## Tên thư mục được hỗ trợ

- model-en-us: tiếng Anh Mỹ
- model-ja: tiếng Nhật
- model-vi: tiếng Việt
- model-ko: tiếng Hàn
- model-cn: tiếng Trung

Ví dụ cấu trúc của model tiếng Nhật:

app/src/main/assets/model-ja/am/final.mdl
app/src/main/assets/model-ja/conf/model.conf
app/src/main/assets/model-ja/graph/...

Thư mục model phải chứa trực tiếp các thư mục như `am`, `conf` và `graph`;
không để chúng bên trong thêm một thư mục ZIP cấp ngoài.

## Ánh xạ ngôn ngữ

- ja hoặc jp -> model-ja
- vi hoặc vn -> model-vi
- ko -> model-ko
- zh hoặc cn -> model-cn
- auto, en hoặc để trống -> model-en-us

Khi model đã được đóng gói, ứng dụng giải nén model vào bộ nhớ riêng trong lần
sử dụng đầu tiên. Việc đóng gói giúp nhận dạng hoạt động ngay khi không có mạng,
nhưng sẽ làm tăng đáng kể kích thước APK.

Nếu không đóng gói model, thiết bị cần kết nối mạng trong lần tải đầu. Các lần
nhận dạng sau không cần mạng miễn là dữ liệu ứng dụng chưa bị xóa.
