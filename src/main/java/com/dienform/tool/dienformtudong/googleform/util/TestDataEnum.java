package com.dienform.tool.dienformtudong.googleform.util;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Enum containing test data for form filling
 */
public class TestDataEnum {

  private static final Random random = new Random();

  private static final List<String> NAMES = Arrays.asList("Nguyễn Văn An", "Trần Thị Bình",
      "Lê Hoàng Cường", "Phạm Mai Dung", "Hoàng Văn Đức", "Nguyễn Thị Giang", "Trần Minh Hải",
      "Đỗ Thu Hương", "Vũ Thành Long", "Ngô Thị Lan", "Bùi Quang Minh", "Phan Thu Nga",
      "Đặng Văn Phong", "Lý Hoài Quân", "Võ Thanh Sơn", "Mai Thị Thanh", "Đinh Văn Tùng",
      "Lương Thị Uyên", "Trương Công Vinh", "Lê Thị Xuân");

  private static final List<String> EMAILS =
      Arrays.asList("nguyenvanan@gmail.com", "tranbinhchi@yahoo.com", "lehoangcuong@hotmail.com",
          "phammai.dung@gmail.com", "hoangduc123@outlook.com", "nguyengiang87@gmail.com",
          "tranminhhai@yahoo.com", "dothuhuong@gmail.com", "vuthanh.long@hotmail.com",
          "ngothilan@gmail.com", "buiquangminh@outlook.com", "phanthunga@yahoo.com",
          "dangvanphong@gmail.com", "lyhoaiquan@hotmail.com", "vothanhson@gmail.com",
          "maithithanh@yahoo.com", "dinhvantung@outlook.com", "luonguyen90@gmail.com",
          "truongcongvinh@yahoo.com", "lexuanthi@gmail.com");

  private static final List<String> FEEDBACK_TEXTS = Arrays.asList(
      "Tôi rất hài lòng với chất lượng sự kiện.",
      "Sự kiện tổ chức rất chuyên nghiệp và thuận tiện.",
      "Nội dung chương trình hữu ích nhưng thời gian hơi ngắn.",
      "Tôi mong có thêm thời gian để giao lưu và kết nối.",
      "Hệ thống âm thanh cần cải thiện hơn trong những lần tới.",
      "Bài thuyết trình rất thú vị và cung cấp nhiều thông tin bổ ích.",
      "Địa điểm tổ chức thuận tiện nhưng thiếu chỗ để xe.",
      "Chất lượng thức ăn nhẹ giữa giờ rất tốt.",
      "Tài liệu phát được chuẩn bị kỹ lưỡng và dễ hiểu.", "Cần thêm nhiều hoạt động tương tác hơn.",
      "Chương trình khá đúng giờ và được tổ chức hợp lý.",
      "Tôi sẽ giới thiệu sự kiện này cho bạn bè và đồng nghiệp.",
      "Nhân viên hỗ trợ rất nhiệt tình và chuyên nghiệp.",
      "Tôi học được nhiều kiến thức mới qua sự kiện này.",
      "Nội dung sự kiện phù hợp với mong đợi của tôi.", "Cần cải thiện hệ thống đăng ký tham dự.",
      "Tôi mong đợi được tham gia những sự kiện tiếp theo.",
      "Không gian tổ chức phù hợp nhưng hơi chật.",
      "Nội dung chương trình cần cập nhật và đổi mới hơn.",
      "Tài liệu trình chiếu rất trực quan và dễ hiểu.");

  private static final List<String> STREET_NAMES =
      Arrays.asList("Lê Lợi", "Nguyễn Huệ", "Trần Hưng Đạo", "Lý Thái Tổ", "Bà Triệu",
          "Điện Biên Phủ", "Lê Duẩn", "Nguyễn Thái Học", "Trần Phú", "Lê Thánh Tông", "Hàng Bài",
          "Tràng Tiền", "Hai Bà Trưng", "Quang Trung", "Phạm Ngũ Lão", "Võ Văn Tần",
          "Nguyễn Đình Chiểu", "Cách Mạng Tháng Tám", "Lê Hồng Phong", "Tôn Đức Thắng");

  private static final List<String> CITIES = Arrays.asList("Hà Nội", "Hồ Chí Minh", "Đà Nẵng",
      "Hải Phòng", "Cần Thơ", "Hội An", "Nha Trang", "Huế", "Vũng Tàu", "Đà Lạt", "Quy Nhơn",
      "Buôn Ma Thuột", "Vinh", "Hạ Long", "Long Xuyên");

  /**
   * Get a random name from the list
   * 
   * @return Random name
   */
  public static String getRandomName() {
    return NAMES.get(random.nextInt(NAMES.size()));
  }

  /**
   * Get a random email from the list
   * 
   * @return Random email
   */
  public static String getRandomEmail() {
    return EMAILS.get(random.nextInt(EMAILS.size()));
  }

  /**
   * Get random feedback text
   * 
   * @return Random feedback text
   */
  public static String getRandomFeedback() {
    return FEEDBACK_TEXTS.get(random.nextInt(FEEDBACK_TEXTS.size()));
  }

  /**
   * Get random street name
   * 
   * @return Random street name
   */
  public static String getRandomStreetName() {
    return STREET_NAMES.get(random.nextInt(STREET_NAMES.size()));
  }

  /**
   * Get random city name
   * 
   * @return Random city name
   */
  public static String getRandomCity() {
    return CITIES.get(random.nextInt(CITIES.size()));
  }

  /**
   * Get a random phone number
   * 
   * @return Random Vietnamese phone number
   */
  public static String getRandomPhoneNumber() {
    return "0" + (900000000 + random.nextInt(99999999));
  }

  /**
   * Get a random address
   * 
   * @return Random Vietnamese address
   */
  public static String getRandomAddress() {
    return "Số " + (1 + random.nextInt(100)) + " đường " + getRandomStreetName() + ", "
        + getRandomCity();
  }
}
