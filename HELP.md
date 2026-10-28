# HomeCare API — hướng dẫn chạy

## Yêu cầu

- JDK 21 (Gradle dùng Java toolchain 21).
- Không cần cài Gradle toàn cục; dùng Gradle Wrapper có sẵn.
- Các service sở hữu dữ liệu cần PostgreSQL. Service có messaging cần RabbitMQ.
- Chạy lệnh từ thư mục `homecare-api/`.

Kiểm tra Gradle nhận các project:

```powershell
.\gradlew.bat projects
```

Monorepo có tám module Spring Boot: `identity-service`, `household-service`,
`provider-service`, `booking-service`, `commerce-service`, `review-service`,
`notification-service` và `assistant-service`. Trách nhiệm và dependency riêng
của từng module nằm trong [services/README.md](services/README.md).

## Chạy một service

Mỗi service mặc định dùng HTTP port `8080`. Ví dụ chạy `assistant-service`:

```powershell
.\gradlew.bat :services:assistant-service:bootRun
```

Các service sở hữu database cần có PostgreSQL đang chạy và ba biến môi trường
database tương ứng trước khi khởi động. Ví dụ cho `booking-service`:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/homecare_booking'
$env:DB_USERNAME = 'postgres'
$env:DB_PASSWORD = '<your-local-password>'
$env:RABBITMQ_HOST = 'localhost'
$env:RABBITMQ_PORT = '5672'
.\gradlew.bat :services:booking-service:bootRun
```

`homecare_booking` chỉ là tên database ví dụ; hãy tạo database trước và trỏ mỗi
service tới database/schema thuộc quyền sở hữu của chính service đó. Đặt
credentials trong environment của shell hoặc secret manager, không commit vào
repository. Hibernate đang dùng `validate`, nên ứng dụng không tự tạo hoặc sửa
schema; hiện chưa có migration được cung cấp.

Các module dùng RabbitMQ mặc định kết nối `localhost:5672`; có thể ghi đè bằng
`RABBITMQ_HOST` và `RABBITMQ_PORT`. Nếu chạy nhiều service đồng thời, gán port
riêng qua `SERVER_PORT` hoặc đối số Spring Boot. Ví dụ:

```powershell
.\gradlew.bat :services:booking-service:bootRun --args='--server.port=8081'
```

## Build và kiểm tra

Build executable JAR cho mọi service:

```powershell
.\gradlew.bat bootJar
```

Build một service:

```powershell
.\gradlew.bat :services:booking-service:bootJar
```

Build output của service được gom dưới `build/services/<service-name>/`, ví dụ
JAR nằm trong `build/services/booking-service/libs/`. Test reports cũng nằm
trong build folder tập trung theo từng service. Chạy test của mọi module:

```powershell
.\gradlew.bat test
```

Output class khi VS Code/Buildship biên dịch project cũng được cấu hình dưới
`build/ide/`: source sets ở `build/ide/classes/<service-name>/<source-set>` và
output mặc định ở `build/ide/default/<service-name>`. Hai nhánh riêng tránh
source-set output lồng trong default output. Sau khi đổi cấu hình, chạy
**Gradle: Refresh Gradle Project** trong VS Code để nạp classpath mới.

Xóa output build:

```powershell
.\gradlew.bat clean
```

Gradle mặc định in log ra terminal. Muốn lưu log của mỗi lần build vào cùng
`build/logs/`, dùng `Tee-Object`:

```powershell
New-Item -ItemType Directory -Force build/logs | Out-Null
$buildLog = Join-Path 'build/logs' ("bootJar-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
.\gradlew.bat bootJar 2>&1 | Tee-Object -FilePath $buildLog
```

## Trạng thái scaffold

Các project hiện là skeleton Spring Boot: chưa có API nghiệp vụ, schema/migration,
RabbitMQ exchange/queue, hay cấu hình authorization hoàn chỉnh. `identity-service`
có Firebase Admin SDK nhưng chưa cấu hình Firebase project/credentials. Không
lưu credential vào source; cần hoàn tất các cấu hình và quyết định bảo mật trước
khi triển khai.

## Tài liệu tham khảo

- [Gradle User Manual](https://docs.gradle.org/current/userguide/userguide.html)
- [Spring Boot Gradle Plugin 4.1.2](https://docs.spring.io/spring-boot/gradle-plugin/)
- [Spring Boot Web MVC](https://docs.spring.io/spring-boot/reference/web/servlet.html)
- [Spring Boot deployment](https://docs.spring.io/spring-boot/how-to/deployment/index.html)
