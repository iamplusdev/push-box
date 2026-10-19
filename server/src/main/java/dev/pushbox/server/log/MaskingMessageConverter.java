package dev.pushbox.server.log;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 把 {@code %msg} 这条通路本身变成没有泄露能力的：占位符展开之后再脱敏，
 * 所以哪怕将来有人写了 {@code log.info("入站 {}", request)}，正文也落不进日志。
 */
public class MaskingMessageConverter extends MessageConverter {

  @Override
  public String convert(ILoggingEvent event) {
    return LogMasking.mask(super.convert(event));
  }
}
