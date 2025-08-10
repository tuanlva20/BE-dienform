package com.dienform.common.service;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.dienform.common.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class GoogleAuthService {

  public static class GoogleUserInfo {
    private String sub;
    private String email;
    private boolean emailVerified;
    private String name;
    private String givenName;
    private String familyName;
    private String picture;
    private String locale;

    public String getSub() {
      return sub;
    }

    public void setSub(String sub) {
      this.sub = sub;
    }

    public String getEmail() {
      return email;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public boolean isEmailVerified() {
      return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
      this.emailVerified = emailVerified;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getGivenName() {
      return givenName;
    }

    public void setGivenName(String givenName) {
      this.givenName = givenName;
    }

    public String getFamilyName() {
      return familyName;
    }

    public void setFamilyName(String familyName) {
      this.familyName = familyName;
    }

    public String getPicture() {
      return picture;
    }

    public void setPicture(String picture) {
      this.picture = picture;
    }

    public String getLocale() {
      return locale;
    }

    public void setLocale(String locale) {
      this.locale = locale;
    }
  }

  private final RestTemplate restTemplate = new RestTemplate();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${google.oauth.client-id}")
  private String googleClientId;

  public GoogleUserInfo verifyIdToken(String idToken) {
    try {
      // Minimal verification by hitting tokeninfo endpoint
      URI uri = new URI("https://oauth2.googleapis.com/tokeninfo?id_token=" + idToken);
      RequestEntity<Void> request = RequestEntity.get(uri).build();
      ResponseEntity<String> response = restTemplate.exchange(request, String.class);
      if (!response.getStatusCode().is2xxSuccessful()) {
        throw new BadRequestException("Invalid Google token");
      }
      JsonNode json = objectMapper.readTree(response.getBody());
      String audience = json.path("aud").asText();
      if (!googleClientId.equals(audience)) {
        throw new BadRequestException("Invalid Google client id");
      }
      GoogleUserInfo info = new GoogleUserInfo();
      info.setSub(json.path("sub").asText());
      info.setEmail(json.path("email").asText());
      info.setEmailVerified("true".equalsIgnoreCase(json.path("email_verified").asText()));
      info.setName(json.path("name").asText());
      info.setGivenName(json.path("given_name").asText());
      info.setFamilyName(json.path("family_name").asText());
      info.setPicture(json.path("picture").asText());
      info.setLocale(json.path("locale").asText());
      return info;
    } catch (Exception primaryEx) {
      // Fallback: Many clients send Google OAuth access_token (starts with ya29.) instead of
      // id_token
      // Try to fetch userinfo using the access token
      try {
        URI userinfoUri = new URI("https://www.googleapis.com/oauth2/v3/userinfo");
        RequestEntity<Void> userInfoRequest = RequestEntity.get(userinfoUri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + idToken).build();
        ResponseEntity<String> userInfoResponse =
            restTemplate.exchange(userInfoRequest, String.class);
        if (!userInfoResponse.getStatusCode().is2xxSuccessful()) {
          throw new BadRequestException("Invalid Google token");
        }
        JsonNode json = objectMapper.readTree(userInfoResponse.getBody());
        GoogleUserInfo info = new GoogleUserInfo();
        info.setSub(json.path("sub").asText());
        info.setEmail(json.path("email").asText());
        info.setEmailVerified(json.path("email_verified").asBoolean(false));
        info.setName(json.path("name").asText());
        info.setGivenName(json.path("given_name").asText());
        info.setFamilyName(json.path("family_name").asText());
        info.setPicture(json.path("picture").asText());
        info.setLocale(json.path("locale").asText());
        return info;
      } catch (Exception fallbackEx) {
        throw new BadRequestException("Failed to verify Google token");
      }
    }
  }
}


