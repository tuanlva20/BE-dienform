package com.dienform.tool.dienformtudong.fillrequest.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;
import lombok.Getter;

@Getter
public class FillRequestCreatedEvent extends ApplicationEvent {

  private final UUID fillRequestId;

  public FillRequestCreatedEvent(Object source, UUID fillRequestId) {
    super(source);
    this.fillRequestId = fillRequestId;
  }
}
