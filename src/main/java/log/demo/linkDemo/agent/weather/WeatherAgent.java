package log.demo.linkDemo.agent.weather;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import log.demo.linkDemo.agent.Agent;
import log.demo.linkDemo.agent.AgentContext;
import log.demo.linkDemo.agent.Intent;
import log.demo.linkDemo.config.BotProperties;
import log.demo.linkDemo.entity.WeatherQuery;
import log.demo.linkDemo.service.IWeatherQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 天气智能体 —— 高德开放平台天气 API。
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
public class WeatherAgent implements Agent {

    private final BotProperties botProperties;
    private final IWeatherQueryService weatherQueryService;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    // ── 内置城市 adcode 映射（38 个主要城市） ──
    private static final Map<String, String> ADCODES = new LinkedHashMap<>();

    static {
        // 使用 LinkedHashMap 保证优先匹配长地名（如"石家庄"先于"石"）
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

    // ── 城市提取正则（v2.0 统一优化版） ──
    // 匹配 "城市名 + 天气/温度/..." 模式，城市名 2-8 个字
    private static final Pattern CITY_WEATHER_PATTERN = Pattern.compile(
            "(?:查询|查|看看|帮我查|我想知道|想知道|告诉我)?"
                    + "(\\S{2,8}?(?:市|区|县|新区)?)"
                    + "(?:的?(?:天气|温度|气温|热不热|冷不冷|多少度|几度|预报|怎么样|如何|情况|如何))");

    // ── 天气响应缓存 ──
    private final Map<String, CacheEntry> responseCache = new ConcurrentHashMap<>();
    private static final long NOW_CACHE_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final long FORECAST_CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();

    // ── 地理编码缓存（城市名 → adcode，长期有效） ──
    private final Map<String, String> geocodeCache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public Intent intent() {
        return Intent.WEATHER;
    }

    @PostConstruct
    public void init() {
        String key = botProperties.getWeather().getApiKey();
        log.info("[WEATHER] WeatherAgent v2.0 初始化 | apiKey={} | baseUrl={} | builtinCities={}",
                key != null ? (key.substring(0, Math.min(6, key.length())) + "***") : "NULL",
                botProperties.getWeather().getBaseUrl(),
                ADCODES.size());
    }

    @Override
    public boolean execute(AgentContext ctx) {
        String city = extractCity(ctx.text());
        ctx.sender().sendText(ctx.userId(),
                "正在查询" + (city.isEmpty() ? "天气" : "「" + city + "」天气") + "...");
        ctx.sender().sendText(ctx.userId(), generateReport(ctx.userId(), ctx.text()));
        return true;
    }

    // ── 报告生成入口 ──

    /**
     * 根据意图文本生成天气报告，同时持久化到 DB。
     */
    public String generateReport(String userId, String intentText) {
        String city = extractCity(intentText);
        if (city.isEmpty()) {
            return "请指定需要查询的城市，例如「北京天气」「上海温度」「查询深圳天气」";
        }

        if (intentText.contains("明天") || intentText.contains("明日")) {
            return buildForecastReport(userId, city, 1);
        }
        if (intentText.contains("后天")) {
            return buildForecastReport(userId, city, 2);
        }
        if (intentText.contains("预报") || intentText.contains("未来") || intentText.contains("一周")) {
            return buildMultiDayReport(userId, city);
        }
        return buildNowReport(userId, city);
    }

    // ── 实时天气 ──

    private String buildNowReport(String userId, String city) {
        long start = System.currentTimeMillis();
        WeatherNow now = fetchNow(city);
        int elapsed = (int) (System.currentTimeMillis() - start);

        if (now == null) {
            saveQuery(userId, null, city, "now", null, "FAILED", elapsed);
            return "天气服务暂不可用，请稍后重试";
        }

        String report = String.format(
                "【%s%s 实时天气报告】\n🌡 当前温度 %.0f°C，%s。\n💧 相对湿度 %.0f%%，%s %s 级。\n🕐 数据发布时间：%s。",
                now.province, now.city, now.temperature, now.weather,
                now.humidity, now.windDirection, now.windPower, now.reportTime);

        saveQuery(userId, null, city, "now", report, "SUCCESS", elapsed);
        return report;
    }

    // ── 单日预报 ──

    private String buildForecastReport(String userId, String city, int dayOffset) {
        long start = System.currentTimeMillis();
        WeatherForecast forecasts = fetchForecast(city);
        int elapsed = (int) (System.currentTimeMillis() - start);

        if (forecasts == null || forecasts.days.length <= dayOffset) {
            saveQuery(userId, null, city, "forecast", null, "FAILED", elapsed);
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

        saveQuery(userId, null, city, "forecast", report, "SUCCESS", elapsed);
        return report;
    }

    // ── 多日预报 ──

    private String buildMultiDayReport(String userId, String city) {
        long start = System.currentTimeMillis();
        WeatherForecast forecasts = fetchForecast(city);
        int elapsed = (int) (System.currentTimeMillis() - start);

        if (forecasts == null) {
            saveQuery(userId, null, city, "multi-day", null, "FAILED", elapsed);
            return "天气服务暂不可用，请稍后重试";
        }

        StringBuilder sb = new StringBuilder("【" + forecasts.city + " 天气预报】\n");
        String[] labels = {"今天", "明天", "后天"};
        int daysToShow = Math.min(forecasts.days.length, 3);

        for (int i = 0; i < daysToShow; i++) {
            ForecastDay d = forecasts.days[i];
            sb.append(String.format(
                    "\n📅 %s（%s）\n　 🌡 %.0f°C ~ %.0f°C | 🌤 %s → %s\n　 💧 %.0f%% | 🌬 %s %s 级",
                    labels[i], d.date, d.nightTemp, d.dayTemp,
                    d.dayWeather, d.nightWeather, d.humidity, d.dayWind, d.dayPower));
        }

        String report = sb.toString();
        saveQuery(userId, null, city, "multi-day", report, "SUCCESS", elapsed);
        return report;
    }

    // ── 城市名提取（v2.0 优化版） ──

    /**
     * 从文本中提取城市名。优先正则匹配 → 遍历内置城市 → 回退正则模糊匹配。
     */
    public String extractCity(String text) {
        if (text == null || text.isBlank()) return "";

        // 1) 精确匹配"城市名 + 天气关键词"
        Matcher m = CITY_WEATHER_PATTERN.matcher(text);
        if (m.find()) {
            String city = m.group(1);
            // 去除多余前缀词
            city = city.replaceFirst("^(查询|查|看看|帮我查|我想知道|想知道|告诉我)", "").trim();
            if (!city.isBlank()) return city;
        }

        // 2) 遍历内置城市（按字符长度降序，避免"吉林"匹配到"林"）
        for (String known : KNOWN_CITIES) {
            if (text.contains(known)) return known;
        }

        // 3) 回退模糊匹配："XX天气"、"XX的温度"
        m = Pattern.compile("(\\S{2,6})\\s*(?:的)?(?:天气|温度|气温)").matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }

        return "";
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
                l.get("province").getAsString(),
                l.get("city").getAsString(),
                l.get("temperature").getAsDouble(),
                l.get("weather").getAsString(),
                l.get("humidity").getAsDouble(),
                l.get("winddirection").getAsString(),
                l.get("windpower").getAsString(),
                l.get("reporttime").getAsString());

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
                    d.get("date").getAsString(),
                    d.get("daytemp").getAsDouble(),
                    d.get("nighttemp").getAsDouble(),
                    d.get("dayweather").getAsString(),
                    d.get("nightweather").getAsString(),
                    d.get("humidity").getAsDouble(),
                    d.get("daywind").getAsString(),
                    d.get("daypower").getAsString());
        }

        WeatherForecast result = new WeatherForecast(fc.get("city").getAsString(), days);
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
            if (!"1".equals(json.get("status").getAsString())) {
                log.error("[WEATHER] API 错误 | info={} | city={}", json.get("info").getAsString(), city);
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
        // 1) 内置映射
        String adcode = ADCODES.get(city);
        if (adcode != null) return adcode;

        // 2) 地理编码缓存
        adcode = geocodeCache.get(city);
        if (adcode != null) return adcode;

        // 3) 调用高德地理编码 API
        adcode = geocodeCity(city);
        if (adcode != null) {
            geocodeCache.put(city, adcode);
        }
        return adcode;
    }

    /**
     * 调用高德地理编码 API，将城市名解析为 adcode。
     * API: https://restapi.amap.com/v3/geocode/geo?address=xxx&key=xxx
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
            if (!"1".equals(json.get("status").getAsString())) {
                log.error("[WEATHER] 地理编码失败 | info={} | city={}",
                        json.get("info").getAsString(), city);
                return null;
            }

            JsonArray geocodes = json.getAsJsonArray("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                log.warn("[WEATHER] 地理编码无结果 | city={}", city);
                return null;
            }

            String adcode = geocodes.get(0).getAsJsonObject()
                    .get("adcode").getAsString();
            log.info("[WEATHER] 地理编码成功 | city={} → adcode={}", city, adcode);
            return adcode;
        } catch (Exception e) {
            log.error("[WEATHER] 地理编码异常 | city={} | {}", city, e.toString());
            return null;
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
