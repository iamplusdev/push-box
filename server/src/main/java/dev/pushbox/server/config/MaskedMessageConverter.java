package dev.pushbox.server.config;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * §13 日志脱敏：把日志消息里的 body/summary/content 字段值替换为 ***，
 * 防止请求体与消息正文落入任何 appender（含 access log 与异常堆栈）。
 */
public class MaskedMessageConverter extends ClassicConverter {

    private static final String[] SENSITIVE_KEYS = {"body", "summary", "content"};

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null) return "";
        for (String key : SENSITIVE_KEYS) {
            msg = maskJsonField(msg, key);
            msg = maskKeyValue(msg, key);
        }
        return msg;
    }

    private String maskJsonField(String msg, String key) {
        // Match "key":"value" or "key": "value" — replace entire quoted value with ***
        return msg.replaceAll("(\"" + key + "\"\\s*:\\s*\")[^\"]*(\")", "$1***$2");
    }

    private String maskKeyValue(String msg, String key) {
        // Match key=value where value is non-whitespace until comma/space/brace/end
        return msg.replaceAll("(" + key + "=)[^\\s,}]+", "$1***");
    }
}
