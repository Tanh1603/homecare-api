# HomeCare API — Hướng Dẫn Cấu Hình & Vận Hành Toàn Diện

Tài liệu này cung cấp toàn bộ hướng dẫn cho lập trình viên để cài đặt môi trường, lấy cấu hình biến môi trường (`.env`), nạp dữ liệu mẫu (seed data), khởi chạy dự án Backend và thực hiện kiểm thử luồng nghiệp vụ (API flow) qua cURL / Postman.

---

## 1. Yêu cầu tiên quyết (Prerequisites)

- **Java JDK 21** trở lên (Khuyên dùng Eclipse Temurin 21 hoặc Oracle OpenJDK 21).
- **Docker & Docker Compose** (Docker Desktop hoặc Docker Engine trên Linux/WSL2).
- **Gradle Wrapper**: Đã tích hợp sẵn trong repo (`.\gradlew.bat` trên Windows, `./gradlew` trên Linux/macOS). Không cần cài Gradle toàn cục.
- Các lệnh trong tài liệu này được thực thi từ thư mục gốc `homecare-api/`:
  ```powershell
  cd c:\Projects\homecare\homecare-api
  ```

---

## 2. Bản đồ kiến trúc & Cổng kết nối (Port & Protocol Map)

Toàn bộ hệ thống Backend gồm 3 container hạ tầng và 7 container dịch vụ ứng dụng, kết nối qua Docker bridge network `homecare-shared`:

| Thành phần | Loại | Cổng Host (Exposed) | Cổng Nội bộ | Giao thức / Vai trò |
| :--- | :--- | :---: | :---: | :--- |
| **`postgres`** | Hạ tầng | `5432` | `5432` | PostgreSQL 17 (6 DBs riêng biệt) |
| **`redis`** | Hạ tầng | `6379` | `6379` | Redis 7 (Cache, Session, Rate Limiter) |
| **`rabbitmq`** | Hạ tầng | `5672` / `15672` | `5672` / `15672` | AMQP 0-9-1 & Web Management UI |
| **`gateway-service`** | Ứng dụng | `8080` | `8080` | Spring Cloud Gateway WebFlux & Swagger Aggregator |
| **`household-service`** | Ứng dụng | `8082` / `9082` | `8082` / `9082` | HTTP REST & gRPC Server (Thiết bị, Bảo hành) |
| **`provider-service`** | Ứng dụng | `8083` / `9083` | `8083` / `9083` | HTTP REST & gRPC Server (Đơn vị sửa chữa, AI Dispatch) |
| **`booking-service`** | Ứng dụng | `8084` / `9084` | `8084` / `9084` | HTTP REST & gRPC Server (Đặt lịch, Saga Orchestrator) |
| **`commerce-service`** | Ứng dụng | `8085` / `9085` | `8085` / `9085` | HTTP REST & gRPC Server (Bán hàng, Đổi trả, VNPAY) |
| **`review-service`** | Ứng dụng | `8086` | `8086` | HTTP REST & gRPC Client (Đánh giá có xác thực) |
| **`notification-service`** | Ứng dụng | `8087` | `8087` | HTTP REST & RabbitMQ Consumer (FCM Push-to-Sync) |

---

## 3. Chuẩn bị biến môi trường (`.env`)

Mỗi microservice sở hữu một file cấu hình `.env` riêng biệt để bảo mật và tách biệt trách nhiệm. Để chuẩn bị nhanh toàn bộ các file `.env` từ các mẫu `.env.example` có sẵn, thực hiện một trong hai cách:

### Cách A: Tự động bằng PowerShell (Khuyên dùng trên Windows)
Chạy script 1 dòng sau từ thư mục `homecare-api/`:

```powershell
# Copy root env cho hạ tầng
if (-not (Test-Path .env)) { Copy-Item .env.example .env }

# Copy env cho từng microservice
Get-ChildItem -Path services -Directory | ForEach-Object {
    $src = Join-Path $_.FullName ".env.example"
    $dst = Join-Path $_.FullName ".env"
    if ((Test-Path $src) -and (-not (Test-Path $dst))) {
        Copy-Item $src $dst
        Write-Host "Created: $dst" -ForegroundColor Green
    }
}
```

### Cách B: Thủ công
Copy thủ công các file:
- `homecare-api/.env.example` $\rightarrow$ `homecare-api/.env`
- `homecare-api/services/identity-service/.env.example` $\rightarrow$ `.env`
- `homecare-api/services/household-service/.env.example` $\rightarrow$ `.env`
- `homecare-api/services/provider-service/.env.example` $\rightarrow$ `.env`
- `homecare-api/services/booking-service/.env.example` $\rightarrow$ `.env`
- `homecare-api/services/commerce-service/.env.example` $\rightarrow$ `.env`
- `homecare-api/services/review-service/.env.example` $\rightarrow$ `.env`
- `homecare-api/services/notification-service/.env.example` $\rightarrow$ `.env`
- `homecare-api/services/gateway-service/.env.example` $\rightarrow$ `.env`

> [!TIP]
> **Tối ưu RAM/CPU khi chạy Docker:**
> Trong tất cả các file `.env` đã được cấu hình sẵn biến:
> `JAVA_TOOL_OPTIONS=-XX:+UseSerialGC -Xms128m -Xmx384m -XX:MaxMetaspaceSize=128m`
> Tùy chọn này giúp giới hạn bộ nhớ JVM tối đa 384MB cho mỗi service và sử dụng bộ thu gom rác đơn luồng (Serial GC), giúp máy tính cá nhân không bị spike CPU hay đơ/treo máy khi chạy cùng lúc 8 microservices.

---

## 4. Hướng dẫn khởi chạy dự án

Có 2 kịch bản vận hành tùy theo mục đích làm việc:

### Kịch bản 1: Chạy 100% bằng Docker Compose (Khuyên dùng cho Tester, Frontend, Demo)

Toàn bộ hệ thống được chia thành 2 file compose: `compose.infra.yml` (Hạ tầng) và `compose.app.yml` (8 Microservices).

#### Bước 1: Khởi động Docker Network & Tầng Hạ tầng
```powershell
# Tạo network dùng chung nếu chưa có
docker network create homecare-shared 2>$null

# Khởi chạy PostgreSQL, Redis và RabbitMQ
docker-compose -f compose.infra.yml up -d
```

Kiểm tra trạng thái sẵn sàng:
```powershell
docker-compose -f compose.infra.yml ps
```
Khi thấy container `postgres` và `rabbitmq` báo `(healthy)`, chuyển sang Bước 2.

#### Bước 2: Build & Khởi động 7 Microservices ứng dụng
```powershell
# Khởi chạy toàn bộ 7 services ứng dụng
docker-compose -f compose.app.yml up -d --build
```

#### Bước 3: Kiểm tra trạng thái & Log
```powershell
# Xem danh sách container đang chạy
docker-compose -f compose.app.yml ps

# Xem log theo thời gian thực của gateway hoặc một service cụ thể
docker-compose -f compose.app.yml logs -f gateway
docker-compose -f compose.app.yml logs -f booking-service
```

#### Dừng hệ thống:
```powershell
docker-compose -f compose.app.yml down
docker-compose -f compose.infra.yml down
```

---

### Kịch bản 2: Chạy kết hợp Hybrid Dev (Khuyên dùng cho Backend Developer đang code)

Khi bạn đang phát triển hoặc debug mã nguồn của 1 hoặc 2 service cụ thể:

1. **Khởi động tầng hạ tầng trong Docker**:
   ```powershell
   docker-compose -f compose.infra.yml up -d
   ```
2. **Chạy các service phụ trợ** (ví dụ chạy trước `gateway-service` trong gradle hoặc docker):
   ```powershell
   .\gradlew.bat :services:gateway-service:bootRun
   ```
3. **Chạy service bạn đang code trực tiếp trên máy host qua Gradle**:
   ```powershell
   # Ví dụ: Chạy booking-service ở chế độ debug
   .\gradlew.bat :services:booking-service:bootRun
   ```
   Service sẽ nạp cấu hình từ `src/main/resources/application.yml` kết nối trực tiếp đến PostgreSQL (`localhost:5432`), RabbitMQ (`localhost:5672`) và Redis (`localhost:6379`).

---

## 5. Dữ liệu mẫu khởi tạo sẵn (Seed Data)

Hệ thống đã tích hợp sẵn cơ chế nạp dữ liệu mẫu (Seed Data) ngay khi khởi tạo:

### 5.1. Tài khoản Supabase Cloud mẫu (Tạo qua `docs/sources/supabase_init.sql`)
Được định nghĩa hoàn chỉnh trong [`docs/sources/supabase_init.sql`](../docs/sources/supabase_init.sql) bao gồm schema `profiles`, `roles`, `permissions`, trigger hook và seed users:
- **Quản trị viên (Admin)**: `admin@homecare.com` / Mật khẩu: `Homecare@2026` (Roles: `ADMIN`).
- **Khách hàng (Customer)**: `customer@homecare.com` / Mật khẩu: `Homecare@2026` (Roles: `CUSTOMER`).
- **Kỹ thuật viên (Technician)**: `technician@homecare.com` / Mật khẩu: `Homecare@2026` (Roles: `TECHNICIAN`).
- **Cửa hàng / Đối tác (Organization)**: `org@homecare.com` / Mật khẩu: `Homecare@2026` (Roles: `ORGANIZATION`).

### 5.2. Cửa hàng & Kỹ thuật viên mẫu (`provider-service` & `commerce-service`)
- **Tổ chức 1**: `Điện máy & Dịch vụ Gia dụng Xanh` (ID: `11111111-1111-1111-1111-111111111111`)
  - Kỹ thuật viên: `Nguyễn Văn A` (`demo-technician`), kinh nghiệm 5 năm, đánh giá 4.8★.
  - Dịch vụ cung cấp: Vệ sinh máy lạnh, Sửa máy giặt, Bảo dưỡng tủ lạnh.
- **Tổ chức 2**: `Trung tâm Cơ điện Lạnh Miền Nam` (ID: `22222222-2222-2222-2222-222222222222`)
  - Kỹ thuật viên: `Trần Văn B`, kinh nghiệm 3 năm, đánh giá 4.6★.
  - Dịch vụ cung cấp: Sửa bình nóng lạnh, Thay lõi lọc nước.

### 5.3. Sản phẩm & Chính sách hậu mãi (`commerce-service`)
- **Thiết bị & Linh kiện lớn (`MAJOR_PART`)**: Bo mạch máy lạnh Inverter, Block máy nén tủ lạnh, Động cơ máy giặt $\rightarrow$ **Có bảo hành 6 - 12 tháng (`warrantyMonths > 0`)**.
- **Vật tư tiêu hao & Dụng cụ (`SMALL_PART`, `TOOL`, `SUPPLY`)**: Ốc vít chuyên dụng, Ống đồng bọc gen, Lõi lọc nước số 1-2-3, Bộ cờ lê lực $\rightarrow$ **Không bảo hành (`warrantyMonths = 0`), có chính sách đổi trả trong 7 - 15 ngày (`returnWindowDays`)**.

---

## 6. Hướng dẫn Test thử luồng nghiệp vụ API (End-to-End API Flow)

Dưới đây là kịch bản cURL hoàn chỉnh kiểm thử qua **API Gateway (`http://localhost:8080`)**:

### Bước 1: Lấy JWT Token từ Supabase Auth
Thực hiện đăng nhập bằng tài khoản test `customer@homecare.com`:

```powershell
$tokenResponse = Invoke-RestMethod -Method Post `
  -Uri "$env:SUPABASE_URL/auth/v1/token?grant_type=password" `
  -Headers @{ "apikey" = "$env:SUPABASE_ANON_KEY"; "Content-Type" = "application/json" } `
  -Body (@{
    email    = "customer@homecare.com"
    password = "Homecare@2026"
  } | ConvertTo-Json)

$TOKEN = $tokenResponse.access_token
Write-Host "Access Token retrieved successfully!" -ForegroundColor Green
```

### Bước 2: Xem danh mục sản phẩm (Commerce Service qua Gateway)
```powershell
curl -X GET "http://localhost:8080/api/v1/products?page=0&size=10" `
  -H "Authorization: Bearer $TOKEN"
```

### Bước 3: Tạo đơn hàng mua linh kiện / thiết bị
```powershell
$orderPayload = @{
  merchantId = "11111111-1111-1111-1111-111111111111"
  customerName = "Nguyễn Văn Demo"
  customerPhone = "0901234567"
  shippingAddress = "123 Đường Nguyễn Huệ, Quận 1, TP.HCM"
  paymentMethod = "VNPAY"
  items = @(
    @{
      productId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001"
      quantity = 1
    }
  )
} | ConvertTo-Json

$orderResult = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/orders" `
  -Headers @{ Authorization = "Bearer $TOKEN"; "Content-Type" = "application/json" } `
  -Body $orderPayload

$ORDER_ID = $orderResult.data.id
$LINE_ID = $orderResult.data.lines[0].id
Write-Host "Created Order: $ORDER_ID, Line: $LINE_ID" -ForegroundColor Cyan
```

### Bước 4: Khởi tạo URL thanh toán VNPAY (Hỗ trợ Web & Mobile Deep Link)
```powershell
# Web gọi mặc định (hoặc truyền returnUrl tùy chỉnh cho Mobile App):
$paymentAttempt = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/orders/$ORDER_ID/payment-attempts" `
  -Headers @{ Authorization = "Bearer $TOKEN"; "Content-Type" = "application/json" } `
  -Body (@{ returnUrl = "homecare://payment-result" } | ConvertTo-Json)

$VNPAY_URL = $paymentAttempt.data.paymentUrl
$TXN_REF = $paymentAttempt.data.txnRef
Write-Host "VNPAY Payment URL: $VNPAY_URL" -ForegroundColor Yellow
```

### Bước 5: Giả lập xác nhận thanh toán thành công (VNPAY IPN Webhook)
```powershell
curl -X GET "http://localhost:8080/api/v1/payments/vnpay/ipn?vnp_TxnRef=$TXN_REF&vnp_ResponseCode=00&vnp_TransactionNo=14599823"
```

### Bước 6: Cập nhật giao hàng thành công $\rightarrow$ Tự động kích hoạt Bảo hành
Cửa hàng xác nhận giao kiện hàng thành công:
```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/orders/$ORDER_ID/lines/$LINE_ID/fulfillment" `
  -Headers @{ Authorization = "Bearer $TOKEN"; "Content-Type" = "application/json" } `
  -Body (@{ status = "DELIVERED"; note = "Đã giao tận tay khách hàng" } | ConvertTo-Json)
```
> Khi đơn hàng chuyển trạng thái `DELIVERED`, Outbox event `order.line.delivered.v1` được đẩy qua RabbitMQ. `household-service` tự động tạo `Device` trong hồ sơ gia đình và kích hoạt `Warranty` 12 tháng với `source = ORDER_AUTO_ACTIVATED`.

### Bước 7: Đặt lịch bảo hành miễn phí (AI Auto-Dispatch Flow)
Khách hàng đặt lịch bảo hành cho thiết bị vừa mua:
```powershell
$bookingPayload = @{
  householdId = "33333333-3333-3333-3333-333333333333"
  deviceId = "44444444-4444-4444-4444-444444444444"
  serviceOfferingId = "55555555-5555-5555-5555-555555555551"
  bookingType = "WARRANTY_CLAIM"
  scheduledStart = "2026-11-10T09:00:00Z"
  scheduledEnd = "2026-11-10T11:00:00Z"
  note = "Máy có tiếng kêu lạ, yêu cầu cửa hàng bảo hành"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/bookings" `
  -Headers @{ Authorization = "Bearer $TOKEN"; "Content-Type" = "application/json" } `
  -Body $bookingPayload
```
> `booking-service` xác thực bảo hành qua kênh **gRPC nội bộ** với `household-service`, gán giá tiền `quotedPrice = 0 VND`, khóa `strictProviderMatch = true` và kích hoạt Saga tự động phân công kỹ thuật viên `demo-technician` của chính cửa hàng đã bán!

### Bước 8: Kiểm tra hộp thư thông báo (Inbox Notification)
```powershell
Invoke-RestMethod -Method Get `
  -Uri "http://localhost:8080/api/v1/notifications" `
  -Headers @{ Authorization = "Bearer $TOKEN" }
```
Nhận thông báo push đồng bộ từ hệ thống (kèm trigger FCM wake-up).

---

## 7. Các địa chỉ UI Quản trị & Công cụ hữu ích

- **Swagger UI Tập trung (Tất cả 6 Domain Services)**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
  *(Có thanh dropdown góc trên cùng bên phải để chuyển đổi giữa Household, Provider, Booking, Commerce, Review, Notification).*
- **Supabase Cloud Dashboard**: [https://supabase.com/dashboard](https://supabase.com/dashboard)
  *(Quản trị người dùng, bảng RBAC, RLS policies và custom token hooks).*
- **RabbitMQ Management Dashboard**: [http://localhost:15672](http://localhost:15672)
  *(Tài khoản: `homecare` / Mật khẩu: `homecare`).*
- **Health check endpoint**:
  - Gateway: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
  - Booking: [http://localhost:8084/actuator/health](http://localhost:8084/actuator/health)
  - Commerce: [http://localhost:8085/actuator/health](http://localhost:8085/actuator/health)

---

## 8. Khắc phục sự cố thường gặp (Troubleshooting)

1. **Lỗi `port is already allocated`**:
   - Kiểm tra xem máy host có đang chạy sẵn PostgreSQL (port 5432), Redis (6379), hoặc ứng dụng nào chiếm port 8080 không bằng lệnh:
     ```powershell
     Get-NetTCPConnection -LocalPort 5432,6379,8080 -ErrorAction SilentlyContinue
     ```
   - Tắt ứng dụng xung đột hoặc đổi port mapping trong `compose.infra.yml` / `compose.app.yml`.

2. **Lỗi xác thực JWT với Supabase Cloud**:
   - Đảm bảo biến `SUPABASE_JWKS_URI` trỏ đúng định dạng: `https://<your-project-ref>.supabase.co/auth/v1/.well-known/jwks.json`.
   - Nếu chạy unit test hoặc offline, services mặc định fallback sang `https://placeholder.supabase.co/auth/v1/.well-known/jwks.json` và sử dụng mock JWT token.

3. **Lỗi `OutOfMemoryError` hoặc máy đơ khi build**:
   - Sử dụng lệnh build có giới hạn bộ nhớ:
     ```powershell
     .\gradlew.bat bootJar --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=384m"
     ```
