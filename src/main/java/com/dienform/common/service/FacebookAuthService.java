package com.dienform.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class FacebookAuthService {

  public static class FacebookUserInfo {
    private String id;
    private String name;
    private String email;
    private String picture;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getEmail() {
      return email;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public String getPicture() {
      return picture;
    }

    public void setPicture(String picture) {
      this.picture = picture;
    }
  }

  private final RestTemplate restTemplate = new RestTemplate();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${facebook.app.id:}")
  private String appId;

  @Value("${facebook.app.secret:}")
  private String appSecret;

  public FacebookUserInfo verifyAccessTokenAndGetUser(String accessToken) {
    try {
      // Debug token endpoint (optional validation)
      // https://graph.facebook.com/debug_token?input_token={token}&access_token={app-id}|{app-secret}
      if (appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank()) {
        URI debugUri = new URI("https://graph.facebook.com/debug_token?input_token=" + accessToken
            + "&access_token=" + appId + "%7C" + appSecret);
        restTemplate.getForObject(debugUri, String.class); // ignore body; if invalid will 400
      }

      // Get user info
      URI uri = new URI(
          "https://graph.facebook.com/me?fields=id,name,email,picture.type(large)&access_token="
              + accessToken);
      RequestEntity<Void> request = RequestEntity.get(uri).build();
      ResponseEntity<String> response = restTemplate.exchange(request, String.class);
      if (!response.getStatusCode().is2xxSuccessful()) {
        throw new RuntimeException("Invalid Facebook token");
      }
      JsonNode json = objectMapper.readTree(response.getBody());
      FacebookUserInfo info = new FacebookUserInfo();
      info.setId(json.path("id").asText());
      info.setName(json.path("name").asText());
      info.setEmail(json.path("email").asText(null));
      JsonNode pictureNode = json.path("picture").path("data").path("url");
      info.setPicture(pictureNode.isMissingNode() ? null : pictureNode.asText());
      return info;
    } catch (Exception e) {
      throw new RuntimeException("Failed to verify Facebook token", e);
    }
  }
}


