package log.summer.aigc.tool.weather;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.config.BotProperties;
import log.summer.aigc.entity.WeatherQuery;
import log.summer.aigc.service.IWeatherQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 天气查询工具 —— 高德开放平台天气 API。
 * <p>
 * <b>v2.0 优化：</b>
 * <ul>
 *   <li>地理编码回退：内置 38 城市 adcode 未命中时，调用高德地理编码 API 自动解析任意城市</li>
 *   <li>响应缓存：天气数据 TTL 缓存（实时天气 5min / 预报 30min），减少重复 API 调用</li>
 *   <li>DB 持久化：每次查询写入 {@link WeatherQuery} 表，记录原始请求/响应/耗时</li>
 *   <li>城市提取优化：统一正则，优先长匹配，兼容更多口语化表达</li>
 * </ul>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final BotProperties botProperties;
    private final IWeatherQueryService weatherQueryService;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    // ── 内置城市 adcode 映射（38 个主要城市） ──
    private static final Map<String, String> ADCODES = new LinkedHashMap<>();

    static {
        ADCODES.put("北京", "110000");
        ADCODES.put("上海", "310000");
        ADCODES.put("广州", "440100");
        ADCODES.put("深圳", "440300");
        ADCODES.put("杭州", "330100");
        ADCODES.put("成都", "510100");
        ADCODES.put("武汉", "420100");
        ADCODES.put("南京", "320100");
        ADCODES.put("重庆", "500000");
        ADCODES.put("天津", "120000");
        ADCODES.put("苏州", "320500");
        ADCODES.put("西安", "610100");
        ADCODES.put("长沙", "430100");
        ADCODES.put("郑州", "410100");
        ADCODES.put("青岛", "370200");
        ADCODES.put("大连", "210200");
        ADCODES.put("厦门", "350200");
        ADCODES.put("福州", "350100");
        ADCODES.put("合肥", "340100");
        ADCODES.put("济南", "370100");
        ADCODES.put("哈尔滨", "230100");
        ADCODES.put("长春", "220100");
        ADCODES.put("沈阳", "210100");
        ADCODES.put("昆明", "530100");
        ADCODES.put("贵阳", "520100");
        ADCODES.put("南宁", "450100");
        ADCODES.put("海口", "460100");
        ADCODES.put("石家庄", "130100");
        ADCODES.put("太原", "140100");
        ADCODES.put("宁波", "330200");
        ADCODES.put("无锡", "320200");
        ADCODES.put("东莞", "441900");
        ADCODES.put("佛山", "440600");
        ADCODES.put("珠海", "440400");
        ADCODES.put("惠州", "441300");
        ADCODES.put("兰州", "620100");
        ADCODES.put("银川", "640100");
        ADCODES.put("西宁", "630100");
        ADCODES.put("乌鲁木齐", "650100");
        ADCODES.put("拉萨", "540100");
        ADCODES.put("呼和浩特", "150100");
        ADCODES.put("南昌", "360100");
    }

    private static final String[] KNOWN_CITIES = ADCODES.keySet().toArray(new String[0]);

    // ── 天气响应缓存 ──
    private final Map<String, CacheEntry> responseCache = new ConcurrentHashMap<>();
    private static final long NOW_CACHE_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final long FORECAST_CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();

    // ── 地理编码缓存（城市名 → adcode，长期有效） ──
    private final Map<String, String> geocodeCache = new ConcurrentHashMap<>();

    @Tool(name = "weather_query", description = "查询指定城市今天或明天的天气情况")
    public ActResult weatherQuery(
            @ToolParam(description = "城市名称，例如 '北京'、'上海'") String city,
            @ToolParam(description = "'today' 或 'tomorrow'") String day) {

        try {
            if (city == null || city.isBlank()) {
                return ActResult.failure("请指定需要查询的城市，例如「北京」「上海」");
            }

            if ("tomorrow".equals(day)) {
                return ActResult.success(buildForecastReport(city, 1));
            }
            return ActResult.success(buildNowReport(city));
        } catch (Exception e) {
            log.error("[WEATHER] 查询失败 | city={} | day={}", city, day, e);
            return ActResult.failure("天气查询异常：" + e.getMessage());
        }
    }

    // ── 实时天气 ──

    private String buildNowReport(String city) {
        long start = System.currentTimeMillis();
        WeatherNow now = fetchNow(city);
        int elapsed = (int) (System.currentTimeMillis() - start);

        if (now == null) {
            saveQuery("", "", city, "now", null, "FAILED", elapsed);
            return "天气服务暂不可用，请稍后重试";
        }

        String report = String.format(
                "【%s%s 实时天气报告】\n🌡 当前温度 %.0f°C，%s。\n💧 相对湿度 %.0f%%，%s %s 级。\n🕐 数据发布时间：%s。",
                now.province, now.city, now.temperature, now.weather,
                now.humidity, now.windDirection, now.windPower, now.reportTime);

        saveQuery("", "", city, "now", report, "SUCCESS", elapsed);
        return report;
    }

    // ── 单日预报 ──

    private String buildForecastReport(String city, int dayOffset) {
        long start = System.currentTimeMillis();
        WeatherForecast forecasts = fetchForecast(city);
        int elapsed = (int) (System.currentTimeMillis() - start);

        if (forecasts == null || forecasts.days == null || forecasts.days.length <= dayOffset) {
            saveQuery("", "", city, "forecast", null, "FAILED", elapsed);
            return "暂无该日天气预报数据";
        }

        ForecastDay day = forecasts.days[dayOffset];
        String label = switch (dayOffset) {
            case 0 -> "今天";
            case 1 -> "明天";
            case 2 -> "后天";
            default -> "";
        };

        String report = String.format(
                "【%s%s天气】（%s）\n🌡 温度 %.0f°C ~ %.0f°C。\n☀ 白天%s，🌙 夜间%s。\n💧 湿度 %.0f%%，🌬 %s %s 级。",
                forecasts.city, label, day.date, day.nightTemp, day.dayTemp,
                day.dayWeather, day.nightWeather, day.humidity, day.dayWind, day.dayPower);

        saveQuery("", "", city, "forecast", report, "SUCCESS", elapsed);
        return report;
    }

    // ── API 调用（带缓存） ──

    private WeatherNow fetchNow(String city) {
        String cacheKey = "now:" + city;
        CacheEntry cached = responseCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("[WEATHER] 缓存命中 | city={} | type=now", city);
            return (WeatherNow) cached.data;
        }

        JsonObject resp = callApi(city, "base");
        if (resp == null) return null;

        JsonArray lives = resp.getAsJsonArray("lives");
        if (lives == null || lives.isEmpty()) return null;

        JsonObject l = lives.get(0).getAsJsonObject();
        WeatherNow now = new WeatherNow(
                safeString(l, "province", ""),
                safeString(l, "city", ""),
                safeDouble(l, "temperature", 0.0),
                safeString(l, "weather", "未知"),
                safeDouble(l, "humidity", 0.0),
                safeString(l, "winddirection", "未知"),
                safeString(l, "windpower", "0"),
                safeString(l, "reporttime", ""));

        responseCache.put(cacheKey, new CacheEntry(now, NOW_CACHE_TTL_MS));
        return now;
    }

    private WeatherForecast fetchForecast(String city) {
        String cacheKey = "forecast:" + city;
        CacheEntry cached = responseCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("[WEATHER] 缓存命中 | city={} | type=forecast", city);
            return (WeatherForecast) cached.data;
        }

        JsonObject resp = callApi(city, "all");
        if (resp == null) return null;

        JsonArray forecasts = resp.getAsJsonArray("forecasts");
        if (forecasts == null || forecasts.isEmpty()) return null;

        JsonObject fc = forecasts.get(0).getAsJsonObject();
        JsonArray casts = fc.getAsJsonArray("casts");
        if (casts == null) return null;

        ForecastDay[] days = new ForecastDay[casts.size()];
        for (int i = 0; i < casts.size(); i++) {
            JsonObject d = casts.get(i).getAsJsonObject();
            days[i] = new ForecastDay(
                    safeString(d, "date", ""),
                    safeDouble(d, "daytemp", 0.0),
                    safeDouble(d, "nighttemp", 0.0),
                    safeString(d, "dayweather", "未知"),
                    safeString(d, "nightweather", "未知"),
                    safeDouble(d, "humidity", 0.0),
                    safeString(d, "daywind", "未知"),
                    safeString(d, "daypower", "0"));
        }

        WeatherForecast result = new WeatherForecast(safeString(fc, "city", city), days);
        responseCache.put(cacheKey, new CacheEntry(result, FORECAST_CACHE_TTL_MS));
        return result;
    }

    /**
     * 调用高德天气 API。adcode 解析策略：
     * <ol>
     *   <li>内置 38 城市映射</li>
     *   <li>地理编码缓存（之前已查过的城市）</li>
     *   <li>高德地理编码 API 动态解析（结果写入缓存）</li>
     * </ol>
     */
    private JsonObject callApi(String city, String extensions) {
        String location = resolveAdcode(city);
        if (location == null) {
            log.error("[WEATHER] 无法解析城市 adcode | city={}", city);
            return null;
        }

        String apiKey = botProperties.getWeather().getApiKey();
        String baseUrl = botProperties.getWeather().getBaseUrl();
        String url = baseUrl + "?city=" + location
                + "&key=" + apiKey + "&extensions=" + extensions;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.error("[WEATHER] HTTP {} | city={} | location={}", resp.statusCode(), city, location);
                return null;
            }

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            String status = safeString(json, "status", "0");
            if (!"1".equals(status)) {
                log.error("[WEATHER] API 错误 | info={} | city={}",
                        safeString(json, "info", "未知"), city);
                return null;
            }
            return json;
        } catch (Exception e) {
            log.error("[WEATHER] API 异常 | city={} | location={} | {}", city, location, e.toString());
            return null;
        }
    }

    // ── 城市名 → adcode 解析 ──

    /**
     * 解析城市名 → adcode。
     * 优先级：内置映射 → 地理编码缓存 → 高德地理编码 API。
     */
    private String resolveAdcode(String city) {
        String adcode = ADCODES.get(city);
        if (adcode != null) return adcode;

        adcode = geocodeCache.get(city);
        if (adcode != null) return adcode;

        adcode = geocodeCity(city);
        if (adcode != null) {
            geocodeCache.put(city, adcode);
        }
        return adcode;
    }

    /**
     * 调用高德地理编码 API，将城市名解析为 adcode。
     */
    private String geocodeCity(String city) {
        String apiKey = botProperties.getWeather().getApiKey();
        String url = "https://restapi.amap.com/v3/geocode/geo"
                + "?address=" + city
                + "&key=" + apiKey;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.error("[WEATHER] 地理编码 HTTP {} | city={}", resp.statusCode(), city);
                return null;
            }

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            if (!"1".equals(safeString(json, "status", "0"))) {
                log.error("[WEATHER] 地理编码失败 | info={} | city={}",
                        safeString(json, "info", "未知"), city);
                return null;
            }

            JsonArray geocodes = json.getAsJsonArray("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                log.warn("[WEATHER] 地理编码无结果 | city={}", city);
                return null;
            }

            String adcode = safeString(geocodes.get(0).getAsJsonObject(), "adcode", "");
            log.info("[WEATHER] 地理编码成功 | city={} → adcode={}", city, adcode);
            return adcode;
        } catch (Exception e) {
            log.error("[WEATHER] 地理编码异常 | city={} | {}", city, e.toString());
            return null;
        }
    }

    // ── 安全 JSON 访问（防 NPE） ──

    private static String safeString(JsonObject obj, String key, String defaultValue) {
        try {
            var el = obj.get(key);
            if (el == null || el.isJsonNull()) return defaultValue;
            return el.getAsString();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static double safeDouble(JsonObject obj, String key, double defaultValue) {
        try {
            var el = obj.get(key);
            if (el == null || el.isJsonNull()) return defaultValue;
            return el.getAsDouble();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // ── DB 持久化 ──

    private void saveQuery(String userId, String messageId, String city,
                           String queryType, String reportText, String status, int apiElapsedMs) {
        try {
            WeatherQuery record = new WeatherQuery();
            record.setUserId(userId);
            record.setMessageId(messageId);
            record.setQueryText(city);
            record.setCity(city);
            record.setQueryType(queryType);
            record.setReportText(reportText);
            record.setApiElapsedMs(apiElapsedMs);
            record.setStatus(status);
            record.setCreatedAt(LocalDateTime.now());
            weatherQueryService.save(record);
        } catch (Exception e) {
            log.warn("[WEATHER] DB 持久化失败（不影响业务） | city={} | {}", city, e.getMessage());
        }
    }

    // ── 清除缓存（管理用） ──

    /** 清除指定城市的天气缓存 */
    public void evictCache(String city) {
        responseCache.remove("now:" + city);
        responseCache.remove("forecast:" + city);
        log.info("[WEATHER] 缓存已清除 | city={}", city);
    }

    /** 清除所有天气缓存 */
    public void evictAllCache() {
        responseCache.clear();
        log.info("[WEATHER] 全部缓存已清除");
    }

    // ── 数据记录 ──

    record WeatherNow(String province, String city, double temperature, String weather,
                      double humidity, String windDirection, String windPower, String reportTime) {
    }

    record ForecastDay(String date, double dayTemp, double nightTemp, String dayWeather,
                       String nightWeather, double humidity, String dayWind, String dayPower) {
    }

    record WeatherForecast(String city, ForecastDay[] days) {
    }

    /**
     * 通用 TTL 缓存条目（非泛型，支持混合类型存储）。
     */
    private static class CacheEntry {
        final Object data;
        final long expireAt;

        CacheEntry(Object data, long ttlMs) {
            this.data = data;
            this.expireAt = System.currentTimeMillis() + ttlMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
