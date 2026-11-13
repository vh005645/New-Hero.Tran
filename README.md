# Hero Tran Launcher — Build trên GitHub Actions

Launcher cho Android TV / Box, viết bằng Java thuần (không Gradle).
File gốc của bạn đã được giữ nguyên (`src/`, `res/`, `AndroidManifest.xml`).
Mình chỉ **sửa `build.sh` + thêm `.github/workflows/build-apk.yml`** để chạy được trên GitHub Actions.

## Tại sao trước đây build trên GitHub Actions bị lỗi?

| Lỗi cũ | Nguyên nhân |
|---|---|
| Không có job nào chạy / `No workflows` | Trong repo **không có thư mục `.github/workflows/`** nên GitHub không biết phải build cái gì. |
| `gradlew: No such file or directory` | Nếu bạn tạo workflow mẫu của Android, nó sẽ gọi `./gradlew` — nhưng project này **không dùng Gradle**, không có `gradlew` / `build.gradle`. |
| `aapt2.exe: No such file or directory` | `build.sh` cũ gọi `aapt2.exe`, `zipalign.exe` — đuôi `.exe` chỉ có trên Windows. Runner của GitHub Actions là **Ubuntu Linux**, không có file `.exe`. |
| `SDK ... /c/Users/TRANHUNGIT/... not found` | `build.sh` cũ hard-code đường dẫn SDK trên máy Windows của bạn: `/c/Users/TRANHUNGIT/AppData/Local/Android/Sdk`. Trên runner Linux đường dẫn này không tồn tại. |
| `keystore ... ../vn-hero-sign.jks not found` | `build.sh` cũ để `KEYSTORE="../vn-hero-sign.jks"` trong khi file `vn-hero-sign.jks` nằm **cùng thư mục** với `build.sh`, không phải thư mục cha. Sau khi `cd` vào thư mục script thì `../` lại trỏ ra ngoài. |
| `python: command not found` | `build.sh` cũ gọi `python`, trên Ubuntu chỉ có `python3`. |

**Code Java không lỗi.** File `HomeActivity.java` (3031 dòng) dùng `getIdentifier()` + `android.R`, không cần `R.java` nên biên dịch bằng `javac` + `aapt2` + `d8` là đủ.

## Cách dùng (chỉ cần làm 1 lần)

### 1. Đẩy code lên GitHub — file phải nằm ở GỐC repo

Giải nén file zip này ra, bạn sẽ thấy cấu trúc:

```
hero-tran-launcher/
├── .github/workflows/build-apk.yml   ← workflow mới
├── AndroidManifest.xml
├── build.sh                          ← đã sửa để chạy Linux + Windows
├── res/
├── src/
├── vn-hero-sign.jks
├── .gitignore
└── README.md
```

Đẩy **toàn bộ nội dung bên trong** lên GitHub, không đẩy cả thư mục cha:

```bash
cd hero-tran-launcher
git init
git add .
git commit -m "Hero Tran Launcher - GitHub Actions build"
git branch -M main
git remote add origin https://github.com/<ten-ban>/<ten-repo>.git
git push -u origin main
```

> ⚠️ Đừng đẩy kiểu `repo/hero Tran_launcher source code/AndroidManifest.xml` (thừa 1 cấp folder có dấu cách). Workflow mới có cơ chế tự tìm `AndroidManifest.xml` ở folder con để chống lỗi này, nhưng để ở gốc là sạch nhất.

### 2. Chạy build

- Vào repo trên GitHub → tab **Actions** → chọn **Build Hero Tran Launcher APK** → bấm **Run workflow** (hoặc chỉ cần `git push` là nó tự chạy).
- Đợi ✅ xanh → kéo xuống phần **Artifacts** → tải `hero-tran-launcher-apk` → trong đó có `tvlauncher.apk`.

### 3. Tạo bản Release (tùy chọn)

```bash
git tag v1.0
git push origin v1.0
```

Workflow sẽ tự tạo Release kèm file `.apk` để bạn gửi link cho người khác tải.

## Ký APK — 2 cách

### Cách A: Chạy ngay không cần cấu hình (đang dùng mặc định)

File `vn-hero-sign.jks` đã có sẵn trong repo, `build.sh` sẽ dùng nó với:

- Alias: `vnhero`
- Mật khẩu: `android`

Bạn push lên là build được ngay.

> ⚠️ Cách này tiện nhưng **không an toàn** vì ai cũng thấy mật khẩu `android` trong code. Sau khi build chạy ổn, nên chuyển sang Cách B và xóa keystore khỏi repo.

### Cách B: Dùng GitHub Secrets (khuyên dùng)

1. Trên máy bạn, encode keystore ra base64:
   ```bash
   # Windows (Git Bash / PowerShell):
   base64 -w 0 vn-hero-sign.jks > keystore.txt
   # macOS / Linux:
   base64 -w0 vn-hero-sign.jks > keystore.txt
   ```
2. Vào repo → **Settings → Secrets and variables → Actions → New repository secret**, tạo 4 secret:
   - `ANDROID_KEYSTORE_BASE64` = toàn bộ nội dung file `keystore.txt`
   - `ANDROID_KEYSTORE_PASSWORD` = `android`
   - `ANDROID_KEY_ALIAS` = `vnhero`
   - `ANDROID_KEY_PASSWORD` = `android`
3. Sau đó xóa `vn-hero-sign.jks` khỏi repo + bỏ comment dòng `vn-hero-sign.jks` trong `.gitignore`, push lại. Workflow sẽ ưu tiên dùng Secrets, không cần file nữa.

## Chạy build ở máy local (Windows)

```bash
# Git Bash, ở thư mục gốc chứa build.sh
./build.sh
# Kết quả: build/tvlauncher.apk
```

Máy cần có Android SDK + JDK 17. Script sẽ tự tìm SDK qua `ANDROID_HOME`, nếu không có thì fallback về `~/Android/Sdk` và đường dẫn cũ trên máy bạn.

## Kiểm tra nhanh nếu vẫn lỗi

- **Actions không chạy:** kiểm tra file có đúng đường dẫn `.github/workflows/build-apk.yml` không, có bị nằm trong folder con không.
- **`apksigner verify failed`:** sai mật khẩu / alias — kiểm tra lại Secrets hoặc file `vn-hero-sign.jks`.
- **`SDK not found`:** workflow đã tự cài SDK, lỗi này chỉ gặp khi chạy local — đặt biến `ANDROID_HOME` trỏ tới thư mục SDK.
