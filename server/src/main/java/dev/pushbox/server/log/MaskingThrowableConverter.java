package dev.pushbox.server.log;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 异常堆栈同样要遮：Jackson/MyBatis 的异常 message 里常带着原始 JSON 片段或 SQL 参数，
 * {@code %msg} 遮住了事件文本，栈顶那行却来自 throwable 自己的 toString。
 */
public class MaskingThrowableConverter extends ThrowableProxyConverter {

  @Override
  public String convert(ILoggingEvent event) {
    if (event.getThrowableProxy() == null) {
      return "";
    }
    // pattern 是 "... - %pbmsg%pbex%n"，栈自己带行首换行：没有异常时一处都不多，
    // 有异常时栈从下一行开始。把 %n 放在 %pbex 之前会每行都多出一个空行。
    return System.lineSeparator() + LogMasking.mask(super.convert(event));
  }
}
