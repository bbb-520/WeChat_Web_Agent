package log.demo.linkDemo;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableScheduling
@MapperScan("log.demo.linkDemo.mapper")
public class ILinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ILinkApplication.class, args);
    }
}