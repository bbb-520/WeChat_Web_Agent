package log.demo.linkDemo.tools.navigation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 导航路线查询工具类 —— 纯逻辑，无状态，不依赖 Spring。
 *
 * <p>封装高德地图开放平台 API 调用：地理编码、驾车/步行/骑行/公交换乘路线规划、路况查询。</p>
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>地理编码：地名 → 经纬度坐标</li>
 *   <li>路线规划：驾车（支持途径点、躲避拥堵）、步行、骑行、公交/地铁换乘</li>
 *   <li>拥堵查询：按道路名或矩形区域查询实时路况</li>
 *   <li>响应解析：将高德 API JSON 转为 {@link RouteResult} 等业务模型</li>
 * </ul>
 *
 * @author fjt
 * @since 2026-07-23
 */
public final class NavigationTools {

    private NavigationTools() { /* 工具类不可实例化 */ }

    private static final Logger log = LoggerFactory.getLogger(NavigationTools.class);

    public static final String AMAP_BASE = "https://restapi.amap.com/v3";

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static final Gson GSON = new Gson();

    /** 单次最多途径点数 */
    public static final int MAX_WAYPOINTS = 8;

    /** 地址中是否包含经纬度坐标 */
    static final Pattern COORD_PATTERN =
            Pattern.compile("^\\s*-?\\d{1,3}\\.\\d+\\s*,\\s*-?\\d{1,3}\\.\\d+\\s*$");

    // ═══════════════════════════════════════════════════════════════
    // 地理编码
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将地名或地址转换为"经度,纬度"坐标字符串。
     *
     * @param apiKey  高德 API Key
     * @param address 地址名称
     * @return 坐标字符串，失败返回 null
     */
    public static String geocode(String apiKey, String address) {
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

            String location = geocodes.get(0).getAsJsonObject()
                    .get("location").getAsString();
            log.debug("[NavTools] 地理编码: {} → {}", address, location);
            return location;

        } catch (Exception e) {
            log.error("[NavTools] 地理编码异常 | address={} | {}", address, e.toString());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 路线规划（总入口）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 路线规划 —— 完整参数。
     *
     * @param apiKey           高德 API Key
     * @param origin           起点（地址名或坐标）
     * @param destination      终点（地址名或坐标）
     * @param mode             出行方式
     * @param waypoints        途径点列表，可为 null
     * @param avoidCongestion  是否躲避拥堵
     * @param avoidRestriction 是否避开限行
     * @return 路线结果，失败返回 null
     */
    public static RouteResult planRoute(String apiKey,
                                         String origin, String destination,
                                         TravelMode mode,
                                         List<String> waypoints,
                                         boolean avoidCongestion,
                                         boolean avoidRestriction) {
        String originCoord = isCoordinate(origin) ? origin : geocode(apiKey, origin);
        String destCoord = isCoordinate(destination) ? destination : geocode(apiKey, destination);

        if (originCoord == null || destCoord == null) {
            log.error("[NavTools] 地理编码失败 | origin={} → {} | dest={} → {}",
                    origin, originCoord, destination, destCoord);
            return null;
        }

        String waypointStr = encodeWaypoints(apiKey, waypoints);

        return switch (mode) {
            case DRIVING -> planDriving(apiKey, origin, destination, originCoord, destCoord,
                    waypointStr, avoidCongestion);
            case WALKING -> planWalking(apiKey, originCoord, destCoord);
            case BICYCLING -> planBicycling(apiKey, originCoord, destCoord);
            case TRANSIT -> planTransit(apiKey, origin, destCoord, destination);
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // 驾车路线
    // ═══════════════════════════════════════════════════════════════

    static RouteResult planDriving(String apiKey,
                                    String origin, String dest,
                                    String originCoord, String destCoord,
                                    String waypointStr,
                                    boolean avoidCongestion) {
        int strategy = avoidCongestion ? 4 : 0;

        StringBuilder urlBuilder = new StringBuilder(AMAP_BASE + "/direction/driving")
                .append("?key=").append(apiKey)
                .append("&origin=").append(originCoord)
                .append("&destination=").append(destCoord)
                .append("&strategy=").append(strategy)
                .append("&extensions=all")
                .append("&show_fields=cost,polyline,navi,tmcs,congestion")
                .append("&origin_name=").append(encode(origin))
                .append("&destination_name=").append(encode(dest));

        if (waypointStr != null && !waypointStr.isBlank()) {
            urlBuilder.append("&waypoints=").append(waypointStr);
        }

        JsonObject json = callApi(urlBuilder.toString(), "驾车路线");
        if (json == null) return null;

        return parseDrivingResponse(json);
    }

    // ═══════════════════════════════════════════════════════════════
    // 步行路线
    // ═══════════════════════════════════════════════════════════════

    static RouteResult planWalking(String apiKey,
                                    String originCoord, String destCoord) {
        String url = AMAP_BASE + "/direction/walking"
                + "?key=" + apiKey
                + "&origin=" + originCoord
                + "&destination=" + destCoord;

        JsonObject json = callApi(url, "步行路线");
        if (json == null) return null;

        return parseWalkingResponse(json);
    }

    // ═══════════════════════════════════════════════════════════════
    // 骑行路线
    // ═══════════════════════════════════════════════════════════════

    static RouteResult planBicycling(String apiKey,
                                      String originCoord, String destCoord) {
        String url = AMAP_BASE + "/direction/bicycling"
                + "?key=" + apiKey
                + "&origin=" + originCoord
                + "&destination=" + destCoord;

        JsonObject json = callApi(url, "骑行路线");
        if (json == null) return null;

        return parseBicyclingResponse(json);
    }

    // ═══════════════════════════════════════════════════════════════
    // 公交/地铁换乘路线
    // ═══════════════════════════════════════════════════════════════

    static RouteResult planTransit(String apiKey,
                                    String origin, String originCoord,
                                    String destination) {
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
    // 拥堵查询
    // ═══════════════════════════════════════════════════════════════

    /**
     * 查询指定道路的实时拥堵状态。
     */
    public static List<TrafficCondition> queryTraffic(String apiKey, String roadName) {
        if (roadName == null || roadName.isBlank()) {
            return List.of();
        }

        String url;
        try {
            url = AMAP_BASE + "/traffic/status/road?key=" + apiKey
                    + "&road=" + URLEncoder.encode(roadName, StandardCharsets.UTF_8)
                    + "&extensions=all";
        } catch (Exception e) {
            log.error("[NavTools] 路况 URL 编码失败 | road={} | {}", roadName, e.toString());
            return List.of();
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("[NavTools] 路况 API HTTP {} | road={}", resp.statusCode(), roadName);
                return List.of();
            }

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            return parseTrafficResponse(roadName, json);

        } catch (Exception e) {
            log.error("[NavTools] 路况查询异常 | road={} | {}", roadName, e.toString());
            return List.of();
        }
    }

    /**
     * 查询指定区域内的拥堵道路列表。
     */
    public static List<TrafficCondition> queryTrafficByRect(String apiKey, String rect) {
        if (rect == null || rect.isBlank()) {
            return List.of();
        }

        String url = AMAP_BASE + "/traffic/status/rectangle?key=" + apiKey
                + "&rectangle=" + rect + "&extensions=all";

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("[NavTools] 矩形路况 HTTP {}", resp.statusCode());
                return List.of();
            }

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            return parseTrafficResponse("区域路况", json);

        } catch (Exception e) {
            log.error("[NavTools] 矩形路况查询异常 | {}", e.toString());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 响应解析
    // ═══════════════════════════════════════════════════════════════

    static RouteResult parseDrivingResponse(JsonObject json) {
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

    static RouteResult parseWalkingResponse(JsonObject json) {
        JsonObject path = getPath(json, "route");
        if (path == null) return null;

        int distance = getInt(path, "distance");
        int duration = getInt(path, "duration");
        List<RouteStep> steps = extractSteps(path);

        return new RouteResult(distance, duration, 0, steps, null, null);
    }

    static RouteResult parseBicyclingResponse(JsonObject json) {
        JsonObject path = getPath(json, "route");
        if (path == null) return null;

        int distance = getInt(path, "distance");
        int duration = getInt(path, "duration");
        List<RouteStep> steps = extractSteps(path);

        return new RouteResult(distance, duration, 0, steps, null, null);
    }

    static RouteResult parseTransitResponse(JsonObject json) {
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

    // ═══════════════════════════════════════════════════════════════
    // 路况解析
    // ═══════════════════════════════════════════════════════════════

    static List<TrafficCondition> parseTmcs(JsonObject path) {
        List<TrafficCondition> list = new ArrayList<>();
        JsonArray tmcs = path.getAsJsonArray("tmcs");
        if (tmcs == null) return list;

        for (int i = 0; i < tmcs.size(); i++) {
            JsonObject t = tmcs.get(i).getAsJsonObject();
            String roadName = getStr(t, "name");
            if (roadName.isEmpty()) roadName = "第" + (i + 1) + "段路";

            int status = getInt(t, "status");
            double speed = getDouble(t, "speed");

            list.add(new TrafficCondition(
                    roadName,
                    TrafficCondition.CongestionLevel.fromAmap(status),
                    speed
            ));
        }
        return list;
    }

    static List<TrafficCondition> parseTrafficResponse(String defaultName, JsonObject json) {
        List<TrafficCondition> list = new ArrayList<>();
        if (!"1".equals(json.get("status").getAsString())) {
            log.warn("[NavTools] 路况 API 业务错误 | info={}", json.get("info").getAsString());
            return list;
        }

        JsonObject trafficInfo = json.getAsJsonObject("trafficinfo");
        if (trafficInfo == null) return list;

        JsonArray roads = trafficInfo.getAsJsonArray("roads");
        if (roads == null) return list;

        for (int i = 0; i < roads.size(); i++) {
            JsonObject r = roads.get(i).getAsJsonObject();
            String name = getStr(r, "name");
            if (name.isEmpty()) name = defaultName + "-" + (i + 1);

            int status = getInt(r, "status");
            double speed = 0;
            if (r.has("speed")) {
                speed = r.get("speed").getAsDouble();
            } else if (r.has("expedite")) {
                speed = r.get("expedite").getAsDouble();
            }

            list.add(new TrafficCondition(
                    name,
                    TrafficCondition.CongestionLevel.fromAmap(status),
                    speed
            ));
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部提取方法
    // ═══════════════════════════════════════════════════════════════

    static List<RouteStep> extractSteps(JsonObject path) {
        List<RouteStep> steps = new ArrayList<>();
        JsonArray stepArr = path.getAsJsonArray("steps");
        if (stepArr != null) {
            for (int i = 0; i < stepArr.size(); i++) {
                JsonObject s = stepArr.get(i).getAsJsonObject();
                steps.add(new RouteStep(
                        getStr(s, "instruction"),
                        getStr(s, "road"),
                        getInt(s, "distance"),
                        getInt(s, "duration"),
                        getStr(s, "action"),
                        getStr(s, "orientation")
                ));
            }
        }
        return steps;
    }

    static List<TransitSegment> extractTransitSegments(JsonObject transit) {
        List<TransitSegment> segments = new ArrayList<>();
        JsonArray segArr = transit.getAsJsonArray("segments");
        if (segArr == null) return segments;

        for (int i = 0; i < segArr.size(); i++) {
            JsonObject seg = segArr.get(i).getAsJsonObject();
            JsonObject busInfo = seg.has("bus")
                    ? seg.getAsJsonObject("bus").getAsJsonArray("buslines").get(0).getAsJsonObject()
                    : null;
            JsonObject walkingInfo = seg.has("walking")
                    ? seg.getAsJsonObject("walking")
                    : null;

            if (busInfo != null) {
                String type = getStr(busInfo, "type");
                TransitSegment.SegmentType segType =
                        "地铁".equals(type) || "SUBWAY".equalsIgnoreCase(type)
                                ? TransitSegment.SegmentType.SUBWAY
                                : TransitSegment.SegmentType.BUS;

                segments.add(new TransitSegment(
                        segType,
                        getStr(busInfo, "name"),
                        getStr(busInfo, "departure_stop"),
                        getStr(busInfo, "arrival_stop"),
                        getInt(busInfo, "station_num"),
                        getInt(busInfo, "distance"),
                        getInt(busInfo, "duration")
                ));
            } else if (walkingInfo != null) {
                segments.add(new TransitSegment(
                        TransitSegment.SegmentType.WALKING,
                        null,
                        getStr(walkingInfo, "origin"),
                        getStr(walkingInfo, "destination"),
                        0,
                        getInt(walkingInfo, "distance"),
                        getInt(walkingInfo, "duration")
                ));
            }
        }
        return segments;
    }

    static List<RouteStep> extractTransitSteps(JsonArray segments) {
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
                        getStr(ws, "instruction"),
                        getStr(ws, "road"),
                        getInt(ws, "distance"),
                        getInt(ws, "duration"),
                        getStr(ws, "action"),
                        getStr(ws, "orientation")
                ));
            }
        }
        return steps;
    }

    // ═══════════════════════════════════════════════════════════════
    // 途径点编码
    // ═══════════════════════════════════════════════════════════════

    static String encodeWaypoints(String apiKey, List<String> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            return null;
        }

        List<String> encoded = new ArrayList<>();
        int count = Math.min(waypoints.size(), MAX_WAYPOINTS);
        for (int i = 0; i < count; i++) {
            String wp = waypoints.get(i).trim();
            if (wp.isBlank()) continue;
            if (!isCoordinate(wp)) {
                String coord = geocode(apiKey, wp);
                if (coord == null) {
                    log.warn("[NavTools] 途径点地理编码失败: {}", wp);
                    continue;
                }
                wp = coord;
            }
            encoded.add(wp);
        }
        return encoded.isEmpty() ? null : String.join(";", encoded);
    }

    // ═══════════════════════════════════════════════════════════════
    // 通用工具方法
    // ═══════════════════════════════════════════════════════════════

    static JsonObject callApi(String url, String label) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("[NavTools] {} HTTP {} | url={}", label, resp.statusCode(), maskKey(url));
                return null;
            }

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            if (!"1".equals(json.get("status").getAsString())) {
                log.error("[NavTools] {} 业务错误 | info={} | infocode={}",
                        label, json.get("info").getAsString(), json.get("infocode").getAsString());
                return null;
            }
            return json;

        } catch (Exception e) {
            log.error("[NavTools] {} 调用异常 | {}", label, e.toString());
            return null;
        }
    }

    static JsonObject getFirstRoute(JsonObject json, String routeKey) {
        return json.has(routeKey) ? json.getAsJsonObject(routeKey) : json;
    }

    /** 从 route.paths[0] 获取单条路径 */
    static JsonObject getPath(JsonObject json, String routeKey) {
        JsonObject route = getFirstRoute(json, routeKey);
        if (route == null) return null;
        JsonArray paths = route.getAsJsonArray("paths");
        if (paths == null || paths.isEmpty()) return null;
        return paths.get(0).getAsJsonObject();
    }

    static boolean isCoordinate(String s) {
        return s != null && COORD_PATTERN.matcher(s.trim()).matches();
    }

    static String extractCity(String address) {
        if (address == null || address.isBlank()) return "北京";
        String[] known = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉",
                "南京", "重庆", "天津", "苏州", "西安", "长沙", "郑州"};
        for (String city : known) {
            if (address.contains(city)) return city;
        }
        return "北京";
    }

    static String encode(String s) {
        if (s == null || s.isBlank()) return "";
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    static String maskKey(String url) {
        return url.replaceAll("key=[^&]+", "key=***");
    }

    // ═══════════════════════════════════════════════════════════════
    // 文本报告生成
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将路线规划结果格式化为可读文本报告。
     *
     * @param result     路线规划结果
     * @param mode       出行方式
     * @param origin     起点名称
     * @param dest       终点名称
     * @return 多行格式化文本
     */
    public static String buildTextReport(RouteResult result, TravelMode mode,
                                         String origin, String dest) {
        if (result == null) return "路线规划失败，请检查地址是否正确";

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

        // 路况摘要
        if (result.traffic() != null && !result.traffic().isEmpty()) {
            sb.append("\n🚦 实时路况：\n");
            for (TrafficCondition tc : result.traffic()) {
                sb.append("  ").append(tc).append("\n");
            }
        }

        // 关键步骤（取前 6 步）
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

        // 公交/地铁换乘详情
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

    /**
     * 返回使用帮助文本。
     */
    public static String helpText() {
        return """
                导航命令：
                /nav 从<起点>到<终点>
                /nav 从<起点>到<终点> 步行
                /nav 从<起点>到<终点> 骑行
                /nav 从<起点>到<终点> 公交
                /traffic <道路名>      —— 查询道路实时拥堵
                示例：/nav 从北京西站到天安门""";
    }

    // JSON 安全取值
    static String getStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : "";
    }

    static int getInt(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }

    static double getDouble(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsDouble(); } catch (Exception e) { return 0; }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内嵌模型类型
    // ═══════════════════════════════════════════════════════════════

    /** 出行方式 */
    public enum TravelMode {
        DRIVING("驾车"),
        WALKING("步行"),
        BICYCLING("骑行"),
        TRANSIT("公交/地铁");

        private final String label;
        TravelMode(String label) { this.label = label; }
        public String label() { return label; }
        public static TravelMode fromLabel(String label) {
            for (TravelMode m : values()) {
                if (m.label.equals(label) || m.name().equalsIgnoreCase(label)) return m;
            }
            return DRIVING;
        }
    }

    /** 路线规划结果 */
    public record RouteResult(
            int distance, int duration, double toll,
            List<RouteStep> steps, TransitPlan transit, List<TrafficCondition> traffic
    ) {
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

    /** 单段导航步骤 */
    public record RouteStep(
            String instruction, String road, int distance, int duration,
            String action, String orientation
    ) {}

    /** 实时路况信息 */
    public record TrafficCondition(String roadName, CongestionLevel level, double speed) {
        public enum CongestionLevel {
            UNKNOWN("未知", "⚪"), CLEAR("畅通", "🟢"), SLOW("缓行", "🟡"),
            CONGESTED("拥堵", "🟠"), SEVERE("严重拥堵", "🔴");
            private final String label;
            private final String icon;
            CongestionLevel(String label, String icon) { this.label = label; this.icon = icon; }
            public String label() { return label; }
            public String icon() { return icon; }
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
            return level.icon() + " " + roadName + " — " + level.label()
                    + (speed > 0 ? "（" + String.format("%.0f", speed) + " km/h）" : "");
        }
    }

    /** 公交换乘段 */
    public record TransitSegment(
            SegmentType type, String routeName, String departureStop,
            String arrivalStop, int stationCount, int distance, int duration
    ) {
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

    /** 公交/地铁换乘完整方案 */
    public record TransitPlan(
            int totalWalkingDistance, int totalStationCount,
            List<TransitSegment> segments, double fare
    ) {}
}