# Kế hoạch xây dựng API cho các tính năng Điền Form

## 1. Phân tích yêu cầu

Dựa trên hình ảnh được cung cấp, ứng dụng cần phát triển các tính năng sau:

### 1.1. Tab "Danh sách điền form"
- Hiển thị danh sách các form đã tạo (Table gồm No, tên form, ngày tạo, hành động)
- Tạo mới form

### 1.2. Tab "Điền theo tỉ lệ mong muốn"
- Thiết lập tỉ lệ điền form theo các đáp án
- Điền form tự động theo tỉ lệ đã thiết lập
- Lập lịch điền form tự động
- Xem chi tiết form (số lượng khảo sát thành công, thất bại, tổng số...)
- Quản lý trạng thái form (đang chạy, hoàn thành, chưa bắt đầu)

### 1.3. Popup liên quan
- Popup "Chi tiết Form": Hiển thị thống kê chi tiết về form
- Popup "Tạo yêu cầu điền form tự động": Cấu hình điền form tự động và hiển thị giá tiền thanh toán
- Popup "Hẹn giờ điền Form": Lập lịch điền form

## 2. Thiết kế Database

### 2.1. Mô hình quan hệ (ERD)

#### Bảng `form`
```
+----------------+--------------+------+-----+---------+-------+
| Field          | Type         | Null | Key | Default | Extra |
+----------------+--------------+------+-----+---------+-------+
| id             | uuid         | NO   | PRI | NULL    |       |
| name           | varchar(255) | NO   |     | NULL    |       |
| edit_link      | varchar(512) | NO   |     | NULL    |       |
| created_at     | datetime     | NO   |     | NULL    |       |
| updated_at     | datetime     | YES  |     | NULL    |       |
| status         | varchar(50)  | NO   |     | NULL    |       |
+----------------+--------------+------+-----+---------+-------+
```

#### Bảng `question`
```
+-------------------------+--------------+------+-----+---------+-------+
| Field                   | Type         | Null | Key | Default | Extra |
+-------------------------+--------------+------+-----+---------+-------+
| id                      | uuid         | NO   | PRI | NULL    |       |
| form_id                 | uuid         | NO   | MUL | NULL    |       |
| title                   | text         | NO   |     | NULL    |       |
| description             | text         | YES  |     | NULL    |       |
| type                    | varchar(50)  | NO   |     | NULL    |       |
| required                | boolean      | NO   |     | false   |       |
| position                | int          | NO   |     | 0       |       |
| created_at              | datetime     | NO   |     | NULL    |       |
+-------------------------+--------------+------+-----+---------+-------+
```

#### Bảng `question_option`
```
+-------------------------+--------------+------+-----+---------+-------+
| Field                   | Type         | Null | Key | Default | Extra |
+-------------------------+--------------+------+-----+---------+-------+
| id                      | uuid         | NO   | PRI | NULL    |       |
| question_id             | uuid         | NO   | MUL | NULL    |       |
| option_text             | text         | NO   |     | NULL    |       |
| option_value            | varchar(255) | YES  |     | NULL    |       |
| position                | int          | NO   |     | 0       |       |
| created_at              | datetime     | NO   |     | NULL    |       |
+-------------------------+--------------+------+-----+---------+-------+
```

#### Bảng `form_statistic`
```
+-------------------------+--------------+------+-----+---------+-------+
| Field                   | Type         | Null | Key | Default | Extra |
+-------------------------+--------------+------+-----+---------+-------+
| id                      | uuid         | NO   | PRI | NULL    |       |
| form_id                 | uuid         | NO   | MUL | NULL    |       |
| total_survey            | int          | NO   |     | 0       |       |
| completed_survey        | int          | NO   |     | 0       |       |
| failed_survey           | int          | NO   |     | 0       |       |
| error_question          | int          | NO   |     | 0       |       |
| last_updated_at         | datetime     | NO   |     | NULL    |       |
+-------------------------+--------------+------+-----+---------+-------+
```

#### Bảng `fill_request`
```
+-------------------------+--------------+------+-----+---------+-------+
| Field                   | Type         | Null | Key | Default | Extra |
+-------------------------+--------------+------+-----+---------+-------+
| id                      | uuid         | NO   | PRI | NULL    |       |
| form_id                 | uuid         | NO   | MUL | NULL    |       |
| survey_count            | int          | NO   |     | 0       |       |
| price_per_survey        | decimal(10,2)| NO   |     | 0.00    |       |
| total_price             | decimal(10,2)| NO   |     | 0.00    |       |
| is_human_like           | boolean      | NO   |     | false   |       |
| created_at              | datetime     | NO   |     | NULL    |       |
| status                  | varchar(50)  | NO   |     | NULL    |       |
+-------------------------+--------------+------+-----+---------+-------+
```

#### Bảng `fill_schedule`
```
+-------------------------+--------------+------+-----+---------+-------+
| Field                   | Type         | Null | Key | Default | Extra |
+-------------------------+--------------+------+-----+---------+-------+
| id                      | uuid         | NO   | PRI | NULL    |       |
| fill_request_id         | uuid         | NO   | MUL | NULL    |       |
| is_scheduled            | boolean      | NO   |     | false   |       |
| is_dynamic_by_time      | boolean      | NO   |     | false   |       |
| timezone                | varchar(50)  | YES  |     | NULL    |       |
| min_interval            | int          | YES  |     | NULL    |       |
| max_interval            | int          | YES  |     | NULL    |       |
| time_windows            | varchar(255) | YES  |     | NULL    |       |
| start_date              | date         | YES  |     | NULL    |       |
| created_at              | datetime     | NO   |     | NULL    |       |
| updated_at              | datetime     | YES  |     | NULL    |       |
+-------------------------+--------------+------+-----+---------+-------+
```

#### Bảng `answer_distribution`
```
+-------------------------+--------------+------+-----+---------+-------+
| Field                   | Type         | Null | Key | Default | Extra |
+-------------------------+--------------+------+-----+---------+-------+
| id                      | uuid         | NO   | PRI | NULL    |       |
| fill_request_id         | uuid         | NO   | MUL | NULL    |       |
| question_id             | uuid         | NO   | MUL | NULL    |       |
| option_id               | uuid         | NO   | MUL | NULL    |       |
| percentage              | int          | NO   |     | 0       |       |
| count                   | int          | NO   |     | 0       |       |
| created_at              | datetime     | NO   |     | NULL    |       |
+-------------------------+--------------+------+-----+---------+-------+
```

#### Bảng `survey_execution`
```
+-------------------------+--------------+------+-----+---------+-------+
| Field                   | Type         | Null | Key | Default | Extra |
+-------------------------+--------------+------+-----+---------+-------+
| id                      | uuid         | NO   | PRI | NULL    |       |
| fill_request_id         | uuid         | NO   | MUL | NULL    |       |
| execution_time          | datetime     | NO   |     | NULL    |       |
| status                  | varchar(50)  | NO   |     | NULL    |       |
| error_message           | text         | YES  |     | NULL    |       |
| response_data           | json         | YES  |     | NULL    |       |
+-------------------------+--------------+------+-----+---------+-------+
```

### 2.2. Mối quan hệ giữa các bảng

- `form` 1:1 `form_statistic`: Mỗi form có một bản ghi thống kê
- `form` 1:N `question`: Mỗi form có nhiều câu hỏi
- `question` 1:N `question_option`: Mỗi câu hỏi có nhiều tùy chọn trả lời
- `form` 1:N `fill_request`: Một form có thể có nhiều yêu cầu điền
- `fill_request` 1:1 `fill_schedule`: Mỗi yêu cầu điền có một lịch điền
- `fill_request` 1:N `answer_distribution`: Mỗi yêu cầu điền có nhiều cấu hình phân phối câu trả lời
- `answer_distribution` N:1 `question`: Mỗi cấu hình phân phối liên kết với một câu hỏi
- `answer_distribution` N:1 `question_option`: Mỗi cấu hình phân phối liên kết với một tùy chọn trả lời
- `fill_request` 1:N `survey_execution`: Mỗi yêu cầu điền có nhiều lần thực hiện điền

## 3. Luồng xử lý

### 3.1. Luồng tạo form và trích xuất thông tin
1. Người dùng tạo form mới, cung cấp tên và URL của Google Form
2. Hệ thống lấy dữ liệu từ Google Form và phân tích
3. Hệ thống lưu thông tin form vào bảng `form`
4. Hệ thống tạo các bản ghi câu hỏi vào bảng `question`
5. Hệ thống tạo các bản ghi tùy chọn trả lời vào bảng `question_option`
6. Hệ thống tạo bản ghi thống kê ban đầu vào bảng `form_statistic`

### 3.2. Luồng tạo yêu cầu điền form
1. Người dùng chọn form và tạo yêu cầu điền form
2. Người dùng cấu hình số lượng khảo sát, giá tiền, và phân phối câu trả lời
3. Hệ thống tạo bản ghi yêu cầu điền vào bảng `fill_request`
4. Hệ thống tạo các bản ghi phân phối câu trả lời vào bảng `answer_distribution`
5. Người dùng cấu hình lịch điền form (tùy chọn)
6. Hệ thống tạo bản ghi lịch điền vào bảng `fill_schedule` (nếu có)
7. Hệ thống thực hiện điền form theo lịch trình hoặc ngay lập tức
8. Mỗi lần điền form, hệ thống tạo bản ghi thực thi vào bảng `survey_execution`
9. Dữ liệu trả lời được lưu trong trường `response_data` của `survey_execution` dưới dạng JSON
10. Hệ thống cập nhật thống kê trong bảng `form_statistic`

## 4. Thiết kế API

### 4.1. API cho Tab "Danh sách điền form"

#### 4.1.1. Lấy danh sách form
- **Endpoint**: `GET /api/form`
- **Query Params**: 
  - `page`: Số trang (mặc định: 0)
  - `size`: Số lượng form mỗi trang (mặc định: 10)
  - `sort`: Tiêu chí sắp xếp (mặc định: "createdAt,desc")
  - `search`: Từ khóa tìm kiếm (tùy chọn)
- **Response**:
```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Trả lời sự kiện",
      "status": "COMPLETED",
      "createdAt": "2025-01-01T12:00:00",
      "statistics": {
        "totalSurvey": 200,
        "completedSurvey": 200,
        "failedSurvey": 0,
        "errorQuestion": 0
      }
    },
    ...
  ],
  "totalElements": 100,
  "totalPages": 10,
  "currentPage": 0,
  "size": 10
}
```

#### 4.1.2. Lấy chi tiết form
- **Endpoint**: `GET /api/form/{formId}`
- **Response**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Trả lời sự kiện",
  "editLink": "https://docs.google.com/forms/d/1vYWosuRPoPgXuUoDX-a7CTqfwS-FLSXnrowEfk",
  "createdAt": "2025-01-01T12:00:00",
  "status": "RUNNING",
  "statistics": {
    "totalSurvey": 200,
    "completedSurvey": 200,
    "failedSurvey": 0,
    "errorQuestion": 200
  },
  "questions": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "title": "Có trả lời dự kiện?",
      "type": "MULTIPLE_CHOICE",
      "required": true,
      "position": 1,
      "options": [
        {
          "id": "550e8400-e29b-41d4-a716-446655440101",
          "text": "TỐT",
          "position": 0
        },
        {
          "id": "550e8400-e29b-41d4-a716-446655440102",
          "text": "KHÔNG TỐT",
          "position": 1
        },
        {
          "id": "550e8400-e29b-41d4-a716-446655440103",
          "text": "KHÔNG TRẢ LỜI",
          "position": 2
        }
      ]
    }
  ]
}
```

#### 4.1.3. Tạo mới form
- **Endpoint**: `POST /api/form`
- **Request Body**:
```json
{
  "name": "Trả lời sự kiện",
  "editLink": "https://docs.google.com/forms/d/1vYWosuRPoPgXuUoDX-a7CTqfwS-FLSXnrowEfk"
}
```
- **Response**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Trả lời sự kiện",
  "editLink": "https://docs.google.com/forms/d/1vYWosuRPoPgXuUoDX-a7CTqfwS-FLSXnrowEfk",
  "createdAt": "2025-04-20T12:00:00",
  "status": "CREATED",
  "questions": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "title": "Có trả lời dự kiện?",
      "type": "MULTIPLE_CHOICE",
      "required": true,
      "position": 1,
      "options": [
        {
          "id": "550e8400-e29b-41d4-a716-446655440101",
          "text": "TỐT",
          "position": 0
        },
        {
          "id": "550e8400-e29b-41d4-a716-446655440102",
          "text": "KHÔNG TỐT",
          "position": 1
        },
        {
          "id": "550e8400-e29b-41d4-a716-446655440103",
          "text": "KHÔNG TRẢ LỜI",
          "position": 2
        }
      ]
    }
  ]
}
```

#### 4.1.4. Xóa form
- **Endpoint**: `DELETE /api/form/{formId}`
- **Response**: `204 No Content`

### 4.2. API cho Tab "Điền theo tỉ lệ mong muốn"

#### 4.2.1. Tạo yêu cầu điền form tự động
- **Endpoint**: `POST /api/form/{formId}/fill-request`
- **Request Body**:
```json
{
  "surveyCount": 500,
  "pricePerSurvey": 350,
  "isHumanLike": true,
  "answerDistributions": [
    {
      "questionId": "550e8400-e29b-41d4-a716-446655440001",
      "optionId": "550e8400-e29b-41d4-a716-446655440103",
      "percentage": 40
    },
    {
      "questionId": "550e8400-e29b-41d4-a716-446655440001",
      "optionId": "550e8400-e29b-41d4-a716-446655440101",
      "percentage": 40
    },
    {
      "questionId": "550e8400-e29b-41d4-a716-446655440001",
      "optionId": "550e8400-e29b-41d4-a716-446655440102",
      "percentage": 20
    }
  ]
}
```
- **Response**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655441000",
  "formId": "550e8400-e29b-41d4-a716-446655440000",
  "surveyCount": 500,
  "pricePerSurvey": 350,
  "totalPrice": 175000,
  "isHumanLike": true,
  "createdAt": "2025-04-20T12:00:00",
  "status": "PENDING",
  "answerDistributions": [
    {
      "questionId": "550e8400-e29b-41d4-a716-446655440001",
      "optionId": "550e8400-e29b-41d4-a716-446655440103",
      "percentage": 40,
      "count": 200,
      "option": {
        "id": "550e8400-e29b-41d4-a716-446655440103",
        "text": "KHÔNG TRẢ LỜI"
      }
    },
    {
      "questionId": "550e8400-e29b-41d4-a716-446655440001",
      "optionId": "550e8400-e29b-41d4-a716-446655440101",
      "percentage": 40,
      "count": 200,
      "option": {
        "id": "550e8400-e29b-41d4-a716-446655440101",
        "text": "TỐT"
      }
    },
    {
      "questionId": "550e8400-e29b-41d4-a716-446655440001",
      "optionId": "550e8400-e29b-41d4-a716-446655440102",
      "percentage": 20,
      "count": 100,
      "option": {
        "id": "550e8400-e29b-41d4-a716-446655440102",
        "text": "KHÔNG TỐT"
      }
    }
  ]
}
```

#### 4.2.2. Cấu hình lịch trình điền form
- **Endpoint**: `POST /api/fill-request/{requestId}/schedule`
- **Request Body**:
```json
{
  "isScheduled": true,
  "isDynamicByTime": true,
  "timezone": "GMT+7",
  "minInterval": 1,
  "maxInterval": 5,
  "timeWindows": "1-2h, 21-22h",
  "startDate": "2025-02-12"
}
```
- **Response**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655442000",
  "fillRequestId": "550e8400-e29b-41d4-a716-446655441000",
  "isScheduled": true,
  "isDynamicByTime": true,
  "timezone": "GMT+7",
  "minInterval": 1,
  "maxInterval": 5,
  "timeWindows": "1-2h, 21-22h",
  "startDate": "2025-02-12",
  "createdAt": "2025-04-20T12:00:00"
}
```

#### 4.2.3. Bắt đầu điền form
- **Endpoint**: `POST /api/fill-request/{requestId}/start`
- **Response**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655441000",
  "status": "RUNNING",
  "message": "Quá trình điền form đã bắt đầu"
}
```

#### 4.2.4. Cập nhật lịch trình điền form
- **Endpoint**: `PUT /api/fill-request/{requestId}/schedule`
- **Request Body**: (tương tự như khi tạo mới)
- **Response**: (tương tự như khi tạo mới, với thêm trường `updatedAt`)

### 4.3. API cho các thao tác chung

#### 4.3.1. Lấy lịch sử điền form
- **Endpoint**: `GET /api/form/{formId}/execution-history`
- **Query Params**: 
  - `page`: Số trang (mặc định: 0)
  - `size`: Số lượng mỗi trang (mặc định: 20)
- **Response**:
```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655443001",
      "executionTime": "2025-04-20T13:15:22",
      "status": "SUCCESS"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655443002",
      "executionTime": "2025-04-20T13:17:45",
      "status": "FAILED",
      "errorMessage": "Network error"
    },
    ...
  ],
  "totalElements": 200,
  "totalPages": 10,
  "currentPage": 0,
  "size": 20
}
```

## 5. Cấu trúc mã nguồn

```
src/
└── main/
    ├── java/
    │   └── com/
    │       └── dienform/
    │           ├── DienformApplication.java
    │           ├── config/
    │           │   └── WebConfig.java
    │           ├── form/
    │           │   ├── controller/
    │           │   │   └── FormController.java
    │           │   ├── dto/
    │           │   │   ├── request/
    │           │   │   │   └── FormRequest.java
    │           │   │   └── response/
    │           │   │       ├── FormResponse.java
    │           │   │       └── FormDetailResponse.java
    │           │   ├── entity/
    │           │   │   └── Form.java
    │           │   ├── repository/
    │           │   │   └── FormRepository.java
    │           │   └── service/
    │           │       └── FormService.java
    │           ├── question/
    │           │   ├── controller/
    │           │   │   └── QuestionController.java
    │           │   ├── dto/
    │           │   │   ├── request/
    │           │   │   │   └── QuestionRequest.java
    │           │   │   └── response/
    │           │   │       └── QuestionResponse.java
    │           │   ├── entity/
    │           │   │   ├── Question.java
    │           │   │   └── QuestionOption.java
    │           │   ├── repository/
    │           │   │   ├── QuestionRepository.java
    │           │   │   └── QuestionOptionRepository.java
    │           │   └── service/
    │           │       └── QuestionService.java
    │           ├── formstatistic/
    │           │   ├── controller/
    │           │   │   └── FormStatisticController.java
    │           │   ├── dto/
    │           │   │   ├── request/
    │           │   │   │   └── FormStatisticRequest.java
    │           │   │   └── response/
    │           │   │       └── FormStatisticResponse.java
    │           │   ├── entity/
    │           │   │   └── FormStatistic.java
    │           │   ├── repository/
    │           │   │   └── FormStatisticRepository.java
    │           │   └── service/
    │           │       └── FormStatisticService.java
    │           ├── fillrequest/
    │           │   ├── controller/
    │           │   │   └── FillRequestController.java
    │           │   ├── dto/
    │           │   │   ├── request/
    │           │   │   │   └── FillRequestDTO.java
    │           │   │   └── response/
    │           │   │       └── FillRequestResponse.java
    │           │   ├── entity/
    │           │   │   └── FillRequest.java
    │           │   ├── repository/
    │           │   │   └── FillRequestRepository.java
    │           │   └── service/
    │           │       └── FillRequestService.java
    │           ├── fillschedule/
    │           │   ├── controller/
    │           │   │   └── FillScheduleController.java
    │           │   ├── dto/
    │           │   │   ├── request/
    │           │   │   │   └── ScheduleDTO.java
    │           │   │   └── response/
    │           │   │       └── ScheduleResponse.java
    │           │   ├── entity/
    │           │   │   └── FillSchedule.java
    │           │   ├── repository/
    │           │   │   └── FillScheduleRepository.java
    │           │   └── service/
    │           │       └── ScheduleService.java
    │           ├── answerdistribution/
    │           │   ├── controller/
    │           │   │   └── AnswerDistributionController.java
    │           │   ├── dto/
    │           │   │   ├── request/
    │           │   │   │   └── AnswerDistributionRequest.java
    │           │   │   └── response/
    │           │   │       └── AnswerDistributionResponse.java
    │           │   ├── entity/
    │           │   │   └── AnswerDistribution.java
    │           │   ├── repository/
    │           │   │   └── AnswerDistributionRepository.java
    │           │   └── service/
    │           │       └── AnswerDistributionService.java
    │           ├── surveyexecution/
    │           │   ├── controller/
    │           │   │   └── SurveyExecutionController.java
    │           │   ├── dto/
    │           │   │   ├── request/
    │           │   │   │   └── SurveyExecutionRequest.java
    │           │   │   └── response/
    │           │   │       └── SurveyExecutionResponse.java
    │           │   ├── entity/
    │           │   │   └── SurveyExecution.java
    │           │   ├── repository/
    │           │   │   └── SurveyExecutionRepository.java
    │           │   └── service/
    │           │       └── ExecutionService.java
    │           ├── common/
    │           │   ├── exception/
    │           │   │   ├── GlobalExceptionHandler.java
    │           │   │   ├── ResourceNotFoundException.java
    │           │   │   └── BadRequestException.java
    │           │   └── util/
    │           │       ├── Constants.java
    │           │       └── FormValidator.java
    │           └── googleform/
    │               └── service/
    │                   └── GoogleFormService.java
    └── resources/
        ├── application.yml
        ├── application-dev.yml
        └── application-prod.yml
```

## 6. Kế hoạch triển khai

### 6.1. Giai đoạn 1: Thiết lập cơ sở dữ liệu và môi trường
- Thiết lập cấu hình database MySQL
- Tạo các entity và repository cơ bản
- Thiết lập cấu hình Spring Boot

### 6.2. Giai đoạn 2: Phát triển các API cơ bản
- API quản lý form (CRUD)
- API đọc và phân tích dữ liệu từ Google Form
- API lưu trữ và quản lý câu hỏi
- API thống kê cơ bản

### 6.3. Giai đoạn 3: Phát triển tính năng điền form
- API tạo yêu cầu điền form
- Cấu hình tỉ lệ điền form
- Thực thi điền form

### 6.4. Giai đoạn 4: Phát triển tính năng lập lịch
- API lập lịch điền form
- Cấu hình thời gian giãn cách
- Thiết lập khoảng thời gian điền trong ngày

### 6.5. Giai đoạn 5: Kiểm thử và tối ưu
- Kiểm thử tích hợp
- Tối ưu hiệu suất
- Xử lý lỗi và edge case

## 7. Ước lượng thời gian
- Giai đoạn 1: 1-2 ngày
- Giai đoạn 2: 3-4 ngày
- Giai đoạn 3: 3-4 ngày
- Giai đoạn 4: 2-3 ngày
- Giai đoạn 5: 2 ngày

Tổng thời gian dự kiến: 11-15 ngày làm việc