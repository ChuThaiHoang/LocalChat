# Local Chat AI

Chatbot AI chạy **hoàn toàn trên điện thoại Android**: mô hình ngôn ngữ nằm ngay trong máy, trò chuyện không cần mạng và không gửi dữ liệu đi đâu. Mạng chỉ dùng một lần để tải mô hình.

Ứng dụng viết bằng Kotlin + Jetpack Compose, chạy mô hình bằng [LiteRT-LM](https://developers.google.com/edge/litert-lm/overview) của Google (cùng bộ máy với ứng dụng Google AI Edge Gallery).

## Tính năng

- Trả lời hiện dần từng chữ, hiển thị Markdown (tiêu đề, danh sách, đoạn code có nút sao chép), nút dừng, cuộc trò chuyện mới.
- Tải mô hình ngay trong app (tải dở thì tiếp tục được), hoặc nhập tệp `.litertlm` từ bộ nhớ máy, hoặc dán link.
- Tự chọn GPU, nếu lỗi thì chuyển sang CPU. Nếu lần nạp trước làm app bị đóng đột ngột, lần mở sau app tự chuyển sang CPU.
- Cài đặt: độ dài ngữ cảnh, system prompt (mặc định trả lời tiếng Việt), nhiệt độ / Top-K / Top-P.
- Hiển thị phần "suy nghĩ" của các mô hình có chế độ reasoning.

## Yêu cầu máy

- Android 12 trở lên, CPU 64-bit **arm64-v8a** (gần như mọi điện thoại từ 2019). Máy ảo x86 không chạy được, hãy dùng máy thật.
- Dung lượng trống: Gemma 4 E2B cần khoảng 3 GB, Gemma 3 1B khoảng 1 GB.
- RAM: Gemma 4 E2B nên từ 8 GB; Gemma 3 1B hợp máy 4–6 GB.

## Lấy file APK

**Cách 1, không cần cài gì lên máy tính (GitHub Actions)**
1. Tạo một repository GitHub và đẩy thư mục này lên nhánh `main`.
2. Vào tab **Actions → Build APK → Run workflow** (cũng tự chạy mỗi lần push).
3. Khi chạy xong, tải artifact `LocalChat-debug-apk`, giải nén ra `app-debug.apk`, chép vào điện thoại và cài (cho phép cài từ nguồn không rõ khi được hỏi).

**Cách 2, Android Studio**
1. Mở thư mục `LocalChat` bằng Android Studio bản mới nhất, đợi Gradle đồng bộ xong (lần đầu cần mạng).
2. Bật Tuỳ chọn nhà phát triển và Gỡ lỗi USB trên điện thoại, cắm cáp, bấm **Run**.
   Hoặc chọn **Build → Build APK(s)**, lấy `app/build/outputs/apk/debug/app-debug.apk`.

**Cách 3, dòng lệnh**: cần JDK 17 và Android SDK (nền tảng 36), rồi chạy `./gradlew :app:assembleDebug`.

## Dùng lần đầu

1. Mở app, bấm **Chọn / tải mô hình**, tải **Gemma 4 E2B** (2,58 GB, nên dùng Wi-Fi). Giữ app mở trong lúc tải; nếu bị ngắt, bấm **Tiếp tục tải**.
2. Bấm **Dùng mô hình này**, đợi nạp (lần đầu có thể mất vài chục giây).
3. Trò chuyện. Thử bật chế độ máy bay: app vẫn trả lời bình thường.

Nếu tải báo lỗi 401/403 (thường gặp với Gemma 3): đăng nhập huggingface.co, chấp nhận điều khoản ở trang mô hình, tạo token quyền Read tại huggingface.co/settings/tokens và dán vào mục **Hugging Face token** trong màn hình Mô hình. Cũng có thể tải tệp bằng trình duyệt rồi dùng **Nhập từ tệp trong máy**.

Muốn một mô hình rất nhẹ cho máy yếu: thử Qwen3-0.6B (khoảng 330 MB, giấy phép Apache-2.0) qua **Tải từ link**:
`https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`
(tên tệp lấy từ trang mô hình; nếu báo 404 hãy kiểm tra lại tên tệp trên trang đó). Mô hình nhỏ như vậy trả lời tiếng Việt sẽ kém hơn Gemma.

## Cấu trúc code

| Tệp | Vai trò |
|---|---|
| `llm/LlmEngine.kt` | Bọc LiteRT-LM: nạp mô hình, GPU→CPU, stream câu trả lời, dừng |
| `llm/ModelStore.kt` | Lưu mô hình trong bộ nhớ riêng của app, nhập tệp từ bộ chọn tệp |
| `llm/ModelDownloader.kt` | Tải HTTPS có tiếp tục (Range); token Hugging Face chỉ gửi tới host gốc |
| `llm/ModelCatalog.kt` | Danh sách mô hình có sẵn trong app (thêm mô hình mới ở đây) |
| `ChatViewModel.kt` | Trạng thái chat, tải, cài đặt |
| `ui/` | Màn hình Chat, Mô hình, Cài đặt (Compose) và bộ đọc Markdown |

Thêm mô hình vào danh sách: thêm một `CatalogModel(...)` trong `ModelCatalog.kt` với link dạng
`https://huggingface.co/litert-community/<repo>/resolve/main/<tệp>.litertlm`. LiteRT-LM hỗ trợ Gemma 4 (E2B, E4B), Gemma 3n, Gemma 3 1B, Qwen2.5, Qwen3-0.6B, Phi-4-mini, FunctionGemma. Danh sách tệp tại huggingface.co/litert-community.

## Phiên bản

Kotlin 2.3.21, Android Gradle Plugin 8.13.2, Gradle 8.14.3, Compose BOM 2025.09.00, compileSdk 36, minSdk 31, JDK 17.

LiteRT-LM đang để `litertlmVersion=latest.release` trong `gradle.properties` (theo tài liệu chính thức). Sau lần build thành công, nên ghim phiên bản để build lặp lại được:
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep litertlm`
rồi đặt `litertlmVersion=<số phiên bản hiện ra>`. Code được đối chiếu với mã nguồn LiteRT-LM ngày 06/11/2026 (v0.20.0 đang phát triển); nếu một bản phát hành mới đổi API và gây lỗi biên dịch, hãy ghim về phiên bản cũ hơn. Nếu Gradle báo "incompatible version of Kotlin", hãy nâng phiên bản Kotlin trong `build.gradle.kts` ở thư mục gốc.

## Xử lý sự cố

- **App đóng đột ngột khi nạp mô hình**: thường do GPU không tương thích hoặc thiếu RAM. Chọn CPU trong Cài đặt, giảm độ dài ngữ cảnh hoặc dùng mô hình nhỏ hơn.
- **"Không nạp được thư viện native"**: máy không phải arm64.
- **Trả lời chậm hoặc máy nóng**: bình thường khi chạy mô hình lớn trên CPU. Thử GPU hoặc mô hình nhỏ hơn.
- **Cuộc trò chuyện quá dài báo lỗi**: bắt đầu cuộc trò chuyện mới, hoặc tăng độ dài ngữ cảnh trong Cài đặt nếu máy đủ RAM.

## Tình trạng kiểm tra

Đã làm: đối chiếu mọi lời gọi LiteRT-LM với mã nguồn của thư viện; biên dịch thử `LlmEngine`, `ModelDownloader` và `MarkdownParser` bằng trình biên dịch Kotlin (bộ đọc Markdown còn được chạy thử với 30.000 chuỗi ngẫu nhiên và từng đoạn đầu của một câu trả lời để mô phỏng streaming).

Chưa làm: build APK đầy đủ và chạy trên điện thoại thật, vì môi trường tạo project không có Android SDK. Phần giao diện Compose và cấu hình Gradle mới được rà soát bằng mắt, nên lần build đầu tiên là phép thử thật. Nếu có lỗi, hãy gửi log để sửa.
