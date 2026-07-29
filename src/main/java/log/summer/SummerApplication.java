package log.summer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("log.summer.aigc.mapper")
@ComponentScan(basePackages = {
        "log.summer.common",
        "log.summer.aigc",
        "log.summer.bot"
})
public class SummerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SummerApplication.class, args);
    }
}
