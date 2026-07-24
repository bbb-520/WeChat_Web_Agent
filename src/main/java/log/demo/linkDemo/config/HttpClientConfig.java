package log.demo.linkDemo.config;

import com.alibaba.dashscope.protocol.ConnectionConfigurations;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 客户端配置 —— OkHttp 连接池与超时。
 *
 * <p>两套 HTTP 客户端：</p>
 * <ol>
 *   <li><b>自定义 OkHttpClient Bean</b> — {@link log.demo.linkDemo.tools.image.ImageGenService#downloadImage}
 *       等直接 OkHttp 调用使用</li>
 *   <li><b>DashScope SDK 内建 OkHttpClient</b> — Spring AI / DashScope SDK 内部使用，
 *       通过环境变量 + {@link Constants#connectionConfigurations} 配置。
 *       <b>注意：</b>{@code spring.ai.dashscope.read-timeout} 不会自动同步到 SDK 层，
 *       必须在此显式注入。</li>
 * </ol>
 *
 * @author bbb
 * @since 2026-07-20
 */
@Configuration
public class HttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    // ── 超时常量 ──
    private static final long CONNECT_TIMEOUT_SEC = 30;
    private static final long READ_TIMEOUT_SEC = 300;      // 5 min — 长文本/图片生成
    private static final long WRITE_TIMEOUT_SEC = 120;     // 2 min — 文件上传
    private static final long CALL_TIMEOUT_SEC = 360;      // 6 min — 总时长上限
    private static final long IDLE_TIMEOUT_SEC = 60;       // 1 min — 连接空闲

    private volatile OkHttpClient okHttpClient;

    // ═══════════════════════════════════════════════════════════════
    // 自定义 OkHttpClient Bean（供直接 OkHttp 调用）
    // ═══════════════════════════════════════════════════════════════

    @Bean
    public OkHttpClient okHttpClient() {
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        log.info("[HTTP] OkHttpClient Bean 已创建 | connect={}s read={}s write={}s call={}s",
                CONNECT_TIMEOUT_SEC, READ_TIMEOUT_SEC, WRITE_TIMEOUT_SEC, CALL_TIMEOUT_SEC);
        return this.okHttpClient;
    }

    // ═══════════════════════════════════════════════════════════════
    // DashScope SDK 内建 HTTP 客户端超时注入
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将超时配置强制注入 DashScope SDK。
     *
     * <p>DashScope SDK 从以下两个来源读取超时（优先级从高到低）：</p>
     * <ol>
     *   <li>{@link Constants#connectionConfigurations} 静态字段</li>
     *   <li>环境变量 {@code DASHSCOPE_READ_TIMEOUT / DASHSCOPE_CONNECTION_TIMEOUT / DASHSCOPE_WRITE_TIMEOUT}</li>
     * </ol>
     *
     * <p>Spring 的 {@code spring.ai.dashscope.read-timeout} 属性仅影响 Spring AI 层，
     * 不会自动同步到 SDK 层 —— 必须在此手动注入。</p>
     */
    @PostConstruct
    public void configureDashScopeSdkTimeouts() {
        // 1) 设置系统属性（SDK 在 OkHttpClient 构建时读取，超时单位为秒）
        System.setProperty(Constants.DASHSCOPE_CONNECTION_TIMEOUT_ENV,
                String.valueOf(CONNECT_TIMEOUT_SEC));
        System.setProperty(Constants.DASHSCOPE_READ_TIMEOUT_ENV,
                String.valueOf(READ_TIMEOUT_SEC));
        System.setProperty(Constants.DASHSCOPE_WRITE_TIMEOUT_ENV,
                String.valueOf(WRITE_TIMEOUT_SEC));
        System.setProperty(Constants.DASHSCOPE_CONNECTION_IDLE_TIMEOUT_ENV,
                String.valueOf(IDLE_TIMEOUT_SEC));

        // 2) 直接替换 SDK 全局 ConnectionConfigurations（避免环境变量未被拾取）
        ConnectionConfigurations config = ConnectionConfigurations.builder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SEC))
                .writeTimeout(Duration.ofSeconds(WRITE_TIMEOUT_SEC))
                .connectionIdleTimeout(Duration.ofSeconds(IDLE_TIMEOUT_SEC))
                .build();
        Constants.connectionConfigurations = config;

        // 3) 设置旧版 int 超时字段（部分 SDK 路径使用）
        Constants.CONNECT_TIMEOUT = (int) CONNECT_TIMEOUT_SEC;
        Constants.CONNECTION_REQUEST_TIMEOUT = (int) CONNECT_TIMEOUT_SEC;

        log.info("[DASHSCOPE-SDK] HTTP 超时已注入 | connect={}s read={}s write={}s idle={}s",
                CONNECT_TIMEOUT_SEC, READ_TIMEOUT_SEC, WRITE_TIMEOUT_SEC, IDLE_TIMEOUT_SEC);
    }

    // ═══════════════════════════════════════════════════════════════
    // 资源清理
    // ═══════════════════════════════════════════════════════════════

    @PreDestroy
    public void destroy() {
        if (okHttpClient != null) {
            log.info("[HTTP] 关闭 OkHttpClient | 释放连接池与线程池");
            okHttpClient.dispatcher().executorService().shutdown();
            okHttpClient.connectionPool().evictAll();
        }
    }
}
