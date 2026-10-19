package dev.pushbox.server;

import dev.pushbox.server.config.PbProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(PbProperties.class)
@EnableScheduling
public class PushboxServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(PushboxServerApplication.class, args);
  }
}
