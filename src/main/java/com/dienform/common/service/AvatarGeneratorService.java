package com.dienform.common.service;

import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AvatarGeneratorService {

  private static final List<String> COLORS = List.of("#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4",
      "#FFEAA7", "#DDA0DD", "#98D8C8", "#F7DC6F", "#BB8FCE", "#85C1E9");

  public String generateAvatar(String name) {
    if (!StringUtils.hasText(name)) {
      log.warn("Name is empty, using default avatar");
      return generateDefaultAvatar();
    }

    String initials = extractInitials(name);
    String color = selectColor(name);
    String svg = createSvg(initials, color);

    return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes());
  }

  private String extractInitials(String name) {
    String[] words = name.trim().split("\\s+");
    StringBuilder initials = new StringBuilder();

    for (String word : words) {
      if (StringUtils.hasText(word)) {
        initials.append(word.charAt(0));
        if (initials.length() >= 2) {
          break; // Chỉ lấy tối đa 2 ký tự đầu
        }
      }
    }

    return initials.toString().toUpperCase();
  }

  private String selectColor(String name) {
    // Sử dụng hash của tên để chọn màu một cách consistent
    int hash = Math.abs(name.hashCode());
    return COLORS.get(hash % COLORS.size());
  }

  private String createSvg(String initials, String color) {
    return String.format(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" viewBox=\"0 0 100 100\">"
            + "<circle cx=\"50\" cy=\"50\" r=\"50\" fill=\"%s\"/>"
            + "<text x=\"50\" y=\"50\" font-family=\"Arial, sans-serif\" font-size=\"40\" font-weight=\"bold\" "
            + "fill=\"white\" text-anchor=\"middle\" dominant-baseline=\"central\">%s</text>"
            + "</svg>",
        color, initials);
  }

  private String generateDefaultAvatar() {
    String svg = createSvg("?", "#9E9E9E");
    return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes());
  }
}
