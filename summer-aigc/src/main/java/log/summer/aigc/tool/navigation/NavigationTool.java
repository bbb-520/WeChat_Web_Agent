package log.summer.aigc.tool.navigation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import log.summer.aigc.config.BotProperties;
import log.summer.aigc.loop.ActResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 导航路线查询工具 —— 封装高德地图开放平台 API 调用。
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>地理编码：地名 → 经纬度坐标</li>
 *   <li>路线规划：驾车（支持躲避拥堵）、步行、骑行、公交/地铁换乘</li>
 *   <li>实时路况查询</li>
 * </ul>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigationTool {

    private final BotProperties botProperties;

    private static final String AMAP_BASE = "https://restapi.amap.com/v3";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();
    private static final Pattern COORD_PATTERN =
            Pattern.compile("^\\s*-?\\d{1,3}\\.\\d+\\s*,\\s*-?\\d{1,3}\\.\\d+\\s*$");

    // ═══════════════════════════════════════════════════════════════
    // @Tool 方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 导航查询。从目的地描述中解析起点、终点和出行方式，返回路线规划结果。
     *
     * @param destination 目的地描述，例如 "从北京西站到天安门"、"从北京西站到天安门 驾车"、"从北京西站到天安门 步行" 等
     * @return 路线规划结果
     */
    @Tool(name = "navigation", description = "导航查询。传入目的地描述如 '从[起点]到[终点]' 或 '从[起点]到[终点] [驾车/步行/骑行/公交]'，返回路线规划结果（距离、时长、步骤等）。也可用于路况查询，传入 'traffic:道路名' 如 'traffic:长安街'。")
    public ActResult navigate(
            @ToolParam(description = "目的地描述，格式如 '从[起点]到[终点]' 或 '从[起点]到[终点] [驾车/步行/骑行/公交]'，也支持 'traffic:[道路名]' 查询路况") String destination) {

        try {
            if (destination == null || destination.isBlank()) {
                return ActResult.failure("请提供目的地描述，例如：从北京西站到天安门、从北京西站到天安门 步行");
            }

            String apiKey = getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return ActResult.failure("高德地图 API Key 未配置");
            }

            // 路况查询
            String trimmed = destination.trim();
            if (trimmed.startsWith("traffic:") || trimmed.startsWith("路况:")) {
                String roadName = trimmed.replaceFirst("^(traffic:|路况:)\\s*", "");
                List<TrafficCondition> traffic = queryTraffic(apiKey, roadName);
                if (traffic.isEmpty()) {
                    return ActResult.success("未查询到「" + roadName + "」的实时路况信息");
                }
                StringBuilder sb = new StringBuilder("🚦 「" + roadName + "」实时路况\n────────────────\n");
                for (TrafficCondition tc : traffic) {
                    sb.append(tc).append("\n");
                }
                sb.append("────────────────");
                return ActResult.success(sb.toString());
            }

            // 解析导航请求
            NavRequest req = parseNavRequest(trimmed);
            if (req == null) {
                return ActResult.failure("无法解析导航请求。请使用格式：从[起点]到[终点] [驾车/步行/骑行/公交]");
            }

            // 路线规划
            RouteResult result = planRoute(apiKey, req.origin, req.destination, req.mode);
            if (result == null) {
                return ActResult.failure("路线规划失败，请检查地址是否正确");
            }

            return ActResult.success(buildTextReport(result, req.mode, req.origin, req.destination));
        } catch (Exception e) {
            log.error("[NAV] 导航查询失败 | destination={}", destination, e);
            return ActResult.failure("导航查询异常：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 导航请求解析
    // ═══════════════════════════════════════════════════════════════

    private record NavRequest(String origin, String destination, TravelMode mode) {}

    private NavRequest parseNavRequest(String text) {
        // 支持格式: 从<起点>到<终点> [模式]
        Pattern p = Pattern.compile("从(.+?)到(.+)");
        java.util.regex.Matcher m = p.matcher(text);
        if (!m.find()) return null;

        String origin = m.group(1).trim();
        String rest = m.group(2).trim();

        String dest;
        TravelMode mode = TravelMode.DRIVING;

        // 检查是否包含出行方式
        for (TravelMode tm : TravelMode.values()) {
            String keyword = tm.label();
            if (rest.endsWith(keyword)) {
                mode = tm;
                dest = rest.substring(0, rest.length() - keyword.length()).trim();
                return new NavRequest(origin, dest, mode);
            }
            // 也检查开头
            String suffix = " " + keyword;
            if (rest.endsWith(suffix)) {
                mode = tm;
                dest = rest.substring(0, rest.length() - suffix.length()).trim();
                return new NavRequest(origin, dest, mode);
            }
        }

        dest = rest;
        return new NavRequest(origin, dest, mode);
    }

    // ═══════════════════════════════════════════════════════════════
    // API Key
    // ═══════════════════════════════════════════════════════════════

    private String getApiKey() {
        if (botProperties.getWeather() == null) return null;
        return botProperties.getWeather().getApiKey();
    }

    // ═══════════════════════════════════════════════════════════════
    // 地理编码
    // ═══════════════════════════════════════════════════════════════

    private String geocode(String apiKey, String address) {
        if (address == null || address.isBlank()) return null;
        if (isCoordinate(address)) return address.trim();

        String encoded;
        try {
            encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }

        String url = AMAP_BASE + "/geocode/geo?key=" + apiKey
                + "&address=" + encoded + "&city=" + encoded;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            if (!"1".equals(json.get("status").getAsString())) return null;

            JsonArray geocodes = json.getAsJsonArray("geocodes");
            if (geocodes == null || geocodes.isEmpty()) return null;

            return geocodes.get(0).getAsJsonObject().get("location").getAsString();
        } catch (Exception e) {
            log.error("[NAV] 地理编码异常 | address={} | {}", address, e.toString());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 路线规划
    // ═══════════════════════════════════════════════════════════════

    private RouteResult planRoute(String apiKey, String origin, String destination, TravelMode mode) {
        String originCoord = isCoordinate(origin) ? origin : geocode(apiKey, origin);
        String destCoord = isCoordinate(destination) ? destination : geocode(apiKey, destination);

        if (originCoord == null || destCoord == null) {
            log.error("[NAV] 地理编码失败 | origin={} → {} | dest={} → {}",
                    origin, originCoord, destination, destCoord);
            return null;
        }

        return switch (mode) {
            case DRIVING -> planDriving(apiKey, origin, destination, originCoord, destCoord);
            case WALKING -> planWalking(apiKey, originCoord, destCoord);
            case BICYCLING -> planBicycling(apiKey, originCoord, destCoord);
            case TRANSIT -> planTransit(apiKey, origin, destCoord, destination);
        };
    }

    private RouteResult planDriving(String apiKey, String origin, String dest,
                                    String originCoord, String destCoord) {
        String url = AMAP_BASE + "/direction/driving"
                + "?key=" + apiKey
                + "&origin=" + originCoord
                + "&destination=" + destCoord
                + "&strategy=0"
                + "&extensions=all"
                + "&show_fields=cost,polyline,navi,tmcs,congestion"
                + "&origin_name=" + encode(origin)
                + "&destination_name=" + encode(dest);

        JsonObject json = callApi(url, "驾车路线");
        if (json == null) return null;

        return parseDrivingResponse(json);
    }

    private RouteResult planWalking(String apiKey, String originCoord, String destCoord) {
        String url = AMAP_BASE + "/direction/walking"
                + "?key=" + apiKey
                + "&origin=" + originCoord
                + "&destination=" + destCoord;

        JsonObject json = callApi(url, "步行路线");
        if (json == null) return null;

        return parseWalkingResponse(json);
    }

    private RouteResult planBicycling(String apiKey, String originCoord, String destCoord) {
        String url = AMAP_BASE + "/direction/bicycling"
                + "?key=" + apiKey
                + "&origin=" + originCoord
                + "&destination=" + destCoord;

        JsonObject json = callApi(url, "骑行路线");
        if (json == null) return null;

        return parseBicyclingResponse(json);
    }

    private RouteResult planTransit(String apiKey, String origin, String originCoord, String destination) {
        String city = extractCity(origin);
        String url = AMAP_BASE + "/direction/transit/integrated"
                + "?key=" + apiKey
                + "&origin=" + originCoord
                + "&destination=" + encode(destination)
                + "&city=" + encode(city)
                + "&cityd=" + encode(city)
                + "&strategy=0"
                + "&extensions=all";

        JsonObject json = callApi(url, "公交路线");
        if (json == null) return null;

        return parseTransitResponse(json);
    }

    // ═══════════════════════════════════════════════════════════════
    // 路况查询
    // ═══════════════════════════════════════════════════════════════

    private List<TrafficCondition> queryTraffic(String apiKey, String roadName) {
        if (roadName == null || roadName.isBlank()) return List.of();

        String url;
        try {
            url = AMAP_BASE + "/traffic/status/road?key=" + apiKey
                    + "&road=" + URLEncoder.encode(roadName, StandardCharsets.UTF_8)
                    + "&extensions=all";
        } catch (Exception e) {
            return List.of();
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            return parseTrafficResponse(roadName, json);
        } catch (Exception e) {
            log.error("[NAV] 路况查询异常 | road={} | {}", roadName, e.toString());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 响应解析
    // ═══════════════════════════════════════════════════════════════

    private RouteResult parseDrivingResponse(JsonObject json) {
        JsonObject route = getFirstRoute(json, "route");
        if (route == null) return null;

        JsonArray paths = route.getAsJsonArray("paths");
        if (paths == null || paths.isEmpty()) return null;

        JsonObject path = paths.get(0).getAsJsonObject();
        int distance = getInt(path, "distance");
        int duration = getInt(path, "duration");
        double cost = getDouble(path, "cost");

        List<RouteStep> steps = extractSteps(path);
        List<TrafficCondition> traffic = parseTmcs(path);

        return new RouteResult(distance, duration, cost, steps, null, traffic);
    }

    private RouteResult parseWalkingResponse(JsonObject json) {
        JsonObject path = getPath(json, "route");
        if (path == null) return null;

        int distance = getInt(path, "distance");
        int duration = getInt(path, "duration");
        List<RouteStep> steps = extractSteps(path);

        return new RouteResult(distance, duration, 0, steps, null, null);
    }

    private RouteResult parseBicyclingResponse(JsonObject json) {
        JsonObject path = getPath(json, "route");
        if (path == null) return null;

        int distance = getInt(path, "distance");
        int duration = getInt(path, "duration");
        List<RouteStep> steps = extractSteps(path);

        return new RouteResult(distance, duration, 0, steps, null, null);
    }

    private RouteResult parseTransitResponse(JsonObject json) {
        JsonObject route = getFirstRoute(json, "route");
        if (route == null) return null;

        JsonArray transits = route.getAsJsonArray("transits");
        if (transits == null || transits.isEmpty()) return null;

        JsonObject transit = transits.get(0).getAsJsonObject();
        int totalDistance = getInt(transit, "distance");
        int totalDuration = getInt(transit, "duration");
        double fare = transit.has("cost") ? transit.get("cost").getAsDouble() : 0;

        List<TransitSegment> transitSegments = extractTransitSegments(transit);
        TransitPlan plan = transitSegments.isEmpty() ? null
                : new TransitPlan(0, 0, transitSegments, fare);

        JsonArray segments = transit.getAsJsonArray("segments");
        List<RouteStep> steps = extractTransitSteps(segments);

        return new RouteResult(totalDistance, totalDuration, 0, steps, plan, null);
    }

    private List<RouteStep> extractSteps(JsonObject path) {
        List<RouteStep> steps = new ArrayList<>();
        JsonArray stepArr = path.getAsJsonArray("steps");
        if (stepArr != null) {
            for (int i = 0; i < stepArr.size(); i++) {
                JsonObject s = stepArr.get(i).getAsJsonObject();
                steps.add(new RouteStep(
                        getStr(s, "instruction"), getStr(s, "road"),
                        getInt(s, "distance"), getInt(s, "duration"),
                        getStr(s, "action"), getStr(s, "orientation")));
            }
        }
        return steps;
    }

    private List<TransitSegment> extractTransitSegments(JsonObject transit) {
        List<TransitSegment> segments = new ArrayList<>();
        JsonArray segArr = transit.getAsJsonArray("segments");
        if (segArr == null) return segments;

        for (int i = 0; i < segArr.size(); i++) {
            JsonObject seg = segArr.get(i).getAsJsonObject();
            JsonObject busInfo = seg.has("bus")
                    ? seg.getAsJsonObject("bus").getAsJsonArray("buslines").get(0).getAsJsonObject()
                    : null;
            JsonObject walkingInfo = seg.has("walking") ? seg.getAsJsonObject("walking") : null;

            if (busInfo != null) {
                String type = getStr(busInfo, "type");
                TransitSegment.SegmentType segType =
                        "地铁".equals(type) || "SUBWAY".equalsIgnoreCase(type)
                                ? TransitSegment.SegmentType.SUBWAY
                                : TransitSegment.SegmentType.BUS;
                segments.add(new TransitSegment(
                        segType, getStr(busInfo, "name"),
                        getStr(busInfo, "departure_stop"), getStr(busInfo, "arrival_stop"),
                        getInt(busInfo, "station_num"), getInt(busInfo, "distance"),
                        getInt(busInfo, "duration")));
            } else if (walkingInfo != null) {
                segments.add(new TransitSegment(
                        TransitSegment.SegmentType.WALKING, null,
                        getStr(walkingInfo, "origin"), getStr(walkingInfo, "destination"),
                        0, getInt(walkingInfo, "distance"), getInt(walkingInfo, "duration")));
            }
        }
        return segments;
    }

    private List<RouteStep> extractTransitSteps(JsonArray segments) {
        List<RouteStep> steps = new ArrayList<>();
        if (segments == null) return steps;

        for (int i = 0; i < segments.size(); i++) {
            JsonObject seg = segments.get(i).getAsJsonObject();
            JsonObject walking = seg.getAsJsonObject("walking");
            if (walking == null) continue;

            JsonArray walkSteps = walking.getAsJsonArray("steps");
            if (walkSteps == null) continue;

            for (int j = 0; j < walkSteps.size(); j++) {
                JsonObject ws = walkSteps.get(j).getAsJsonObject();
                steps.add(new RouteStep(
                        getStr(ws, "instruction"), getStr(ws, "road"),
                        getInt(ws, "distance"), getInt(ws, "duration"),
                        getStr(ws, "action"), getStr(ws, "orientation")));
            }
        }
        return steps;
    }

    private List<TrafficCondition> parseTmcs(JsonObject path) {
        List<TrafficCondition> list = new ArrayList<>();
        JsonArray tmcs = path.getAsJsonArray("tmcs");
        if (tmcs == null) return list;

        for (int i = 0; i < tmcs.size(); i++) {
            JsonObject t = tmcs.get(i).getAsJsonObject();
            String roadName = getStr(t, "name");
            if (roadName.isEmpty()) roadName = "第" + (i + 1) + "段路";
            int status = getInt(t, "status");
            double speed = getDouble(t, "speed");
            list.add(new TrafficCondition(roadName, TrafficCondition.CongestionLevel.fromAmap(status), speed));
        }
        return list;
    }

    private List<TrafficCondition> parseTrafficResponse(String defaultName, JsonObject json) {
        List<TrafficCondition> list = new ArrayList<>();
        if (!"1".equals(json.get("status").getAsString())) return list;

        JsonObject trafficInfo = json.getAsJsonObject("trafficinfo");
        if (trafficInfo == null) return list;

        JsonArray roads = trafficInfo.getAsJsonArray("roads");
        if (roads == null) return list;

        for (int i = 0; i < roads.size(); i++) {
            JsonObject r = roads.get(i).getAsJsonObject();
            String name = getStr(r, "name");
            if (name.isEmpty()) name = defaultName + "-" + (i + 1);
            int status = getInt(r, "status");
            double speed = r.has("speed") ? r.get("speed").getAsDouble()
                    : r.has("expedite") ? r.get("expedite").getAsDouble() : 0;
            list.add(new TrafficCondition(name, TrafficCondition.CongestionLevel.fromAmap(status), speed));
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════════════
    // 文本报告生成
    // ═══════════════════════════════════════════════════════════════

    private String buildTextReport(RouteResult result, TravelMode mode, String origin, String dest) {
        StringBuilder sb = new StringBuilder();
        sb.append("🗺 路线规划 —— ").append(mode.label()).append("\n");
        sb.append("────────────────\n");
        sb.append("起点：").append(origin).append("\n");
        sb.append("终点：").append(dest).append("\n");
        sb.append("────────────────\n");
        sb.append("📏 总距离：").append(result.distanceFormatted()).append("\n");
        sb.append("⏱ 预计耗时：").append(result.durationFormatted()).append("\n");

        if (mode == TravelMode.DRIVING && result.toll() > 0) {
            sb.append("💰 过路费：").append(String.format("%.0f", result.toll())).append(" 元\n");
        }

        if (result.traffic() != null && !result.traffic().isEmpty()) {
            sb.append("\n🚦 实时路况：\n");
            for (TrafficCondition tc : result.traffic()) {
                sb.append("  ").append(tc).append("\n");
            }
        }

        if (result.steps() != null && !result.steps().isEmpty()) {
            sb.append("\n📋 导航步骤：\n");
            int maxSteps = Math.min(result.steps().size(), 6);
            for (int i = 0; i < maxSteps; i++) {
                RouteStep step = result.steps().get(i);
                sb.append(String.format("  %d. %s（%s）%n",
                        i + 1,
                        step.instruction().length() > 40
                                ? step.instruction().substring(0, 40) + "..."
                                : step.instruction(),
                        step.distance() >= 1000
                                ? String.format("%.1f公里", step.distance() / 1000.0)
                                : step.distance() + "米"));
            }
            if (result.steps().size() > 6) {
                sb.append("  ... 共 ").append(result.steps().size()).append(" 步\n");
            }
        }

        if (result.transit() != null && result.transit().segments() != null) {
            sb.append("\n🚇 换乘方案：\n");
            for (TransitSegment seg : result.transit().segments()) {
                sb.append("  ").append(seg).append("\n");
            }
            if (result.transit().fare() > 0) {
                sb.append("💰 预计票价：").append(String.format("%.0f", result.transit().fare())).append(" 元\n");
            }
        }

        sb.append("────────────────");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 通用工具方法
    // ═══════════════════════════════════════════════════════════════

    private JsonObject callApi(String url, String label) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            if (!"1".equals(json.get("status").getAsString())) return null;
            return json;
        } catch (Exception e) {
            log.error("[NAV] {} 调用异常 | {}", label, e.toString());
            return null;
        }
    }

    private JsonObject getFirstRoute(JsonObject json, String routeKey) {
        return json.has(routeKey) ? json.getAsJsonObject(routeKey) : json;
    }

    private JsonObject getPath(JsonObject json, String routeKey) {
        JsonObject route = getFirstRoute(json, routeKey);
        if (route == null) return null;
        JsonArray paths = route.getAsJsonArray("paths");
        if (paths == null || paths.isEmpty()) return null;
        return paths.get(0).getAsJsonObject();
    }

    private boolean isCoordinate(String s) {
        return s != null && COORD_PATTERN.matcher(s.trim()).matches();
    }

    private String extractCity(String address) {
        if (address == null || address.isBlank()) return "北京";
        String[] known = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉",
                "南京", "重庆", "天津", "苏州", "西安", "长沙", "郑州"};
        for (String city : known) {
            if (address.contains(city)) return city;
        }
        return "北京";
    }

    private String encode(String s) {
        if (s == null || s.isBlank()) return "";
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private String getStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private int getInt(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }

    private double getDouble(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsDouble(); } catch (Exception e) { return 0; }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内嵌模型类型
    // ═══════════════════════════════════════════════════════════════

    public enum TravelMode {
        DRIVING("驾车"), WALKING("步行"), BICYCLING("骑行"), TRANSIT("公交/地铁");

        private final String label;
        TravelMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record RouteResult(
            int distance, int duration, double toll,
            List<RouteStep> steps, TransitPlan transit, List<TrafficCondition> traffic) {
        public String distanceFormatted() {
            if (distance >= 1000) return String.format("%.1f 公里", distance / 1000.0);
            return distance + " 米";
        }
        public String durationFormatted() {
            if (duration >= 3600)
                return String.format("%d时%d分", duration / 3600, (duration % 3600) / 60);
            if (duration >= 60) return String.format("%d分钟", duration / 60);
            return duration + "秒";
        }
    }

    public record RouteStep(
            String instruction, String road, int distance, int duration,
            String action, String orientation) {}

    public record TrafficCondition(String roadName, CongestionLevel level, double speed) {
        public enum CongestionLevel {
            UNKNOWN("未知", "⚪"), CLEAR("畅通", "🟢"), SLOW("缓行", "🟡"),
            CONGESTED("拥堵", "🟠"), SEVERE("严重拥堵", "🔴");
            private final String label;
            private final String icon;
            CongestionLevel(String label, String icon) { this.label = label; this.icon = icon; }
            public static CongestionLevel fromAmap(int status) {
                return switch (status) {
                    case 1 -> CLEAR; case 2 -> SLOW;
                    case 3 -> CONGESTED; case 4 -> SEVERE;
                    default -> UNKNOWN;
                };
            }
        }
        @Override
        public String toString() {
            return level.icon + " " + roadName + " — " + level.label()
                    + (speed > 0 ? "（" + String.format("%.0f", speed) + " km/h）" : "");
        }
    }

    public record TransitSegment(
            SegmentType type, String routeName, String departureStop,
            String arrivalStop, int stationCount, int distance, int duration) {
        public enum SegmentType { BUS, SUBWAY, WALKING }
        @Override
        public String toString() {
            return switch (type) {
                case SUBWAY -> "🚇 " + routeName + "（" + departureStop + " → " + arrivalStop
                        + "，" + stationCount + "站）";
                case BUS -> "🚌 " + routeName + "（" + departureStop + " → " + arrivalStop
                        + "，" + stationCount + "站）";
                case WALKING -> "🚶 步行 " + (distance >= 1000
                        ? String.format("%.1f公里", distance / 1000.0) : distance + "米");
            };
        }
    }

    public record TransitPlan(
            int totalWalkingDistance, int totalStationCount,
            List<TransitSegment> segments, double fare) {}
}
