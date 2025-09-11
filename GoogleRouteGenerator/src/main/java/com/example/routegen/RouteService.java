package com.example.routegen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RouteService - single-file library + CLI wrapper
 *
 * New public API:
 *  - RouteComparisonResult getAllRouteMetrics(startLat, startLon, endLat, endLon, apiKey)
 *      -> info about every returned route, chosen shortest route index, and counts.
 *
 * Keep existing APIs:
 *  - generateRoute(...) and generateRouteJson(...)
 *  - getShortestRouteMetrics(...) (returns RouteMetrics for chosen route)
 */
public class RouteService {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // ---------- Public DTOs ----------
    public static class RoutePoint {
        public final double lat;
        public final double lon;
        public RoutePoint(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    public static class RouteMetrics {
        public final double distanceMeters;
        public final double durationSeconds;
        public final int routeIndex; // 0-based index among returned alternatives

        public RouteMetrics(double distanceMeters, double durationSeconds, int routeIndex) {
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.routeIndex = routeIndex;
        }

        @Override
        public String toString() {
            return "RouteMetrics{index=" + routeIndex + ", distanceMeters=" + distanceMeters + ", durationSeconds=" + durationSeconds + '}';
        }
    }

    /**
     * Comparison result: metrics for all returned routes and chosen index (shortest by distance).
     */
    public static class RouteComparisonResult {
        public final List<RouteMetrics> routes;
        public final int chosenIndex;
        public final int totalRoutes;

        public RouteComparisonResult(List<RouteMetrics> routes, int chosenIndex) {
            this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
            this.chosenIndex = chosenIndex;
            this.totalRoutes = routes == null ? 0 : routes.size();
        }

        @Override
        public String toString() {
            return "RouteComparisonResult{totalRoutes=" + totalRoutes + ", chosenIndex=" + chosenIndex + ", routes=" + routes + '}';
        }
    }

    // ---------- Public library methods ----------

    public static List<RoutePoint> generateRoute(double startLat, double startLon,
                                                 double endLat, double endLon,
                                                 double intervalMeters,
                                                 String apiKey) throws IOException {
        requireApiKey(apiKey);
        List<LatLng> pts = RouteGeneratorCore.generatePointsFromDirections(startLat, startLon, endLat, endLon, intervalMeters, apiKey);
        return pts.stream().map(p -> new RoutePoint(p.lat, p.lon)).collect(Collectors.toList());
    }

    public static String generateRouteJson(double startLat, double startLon,
                                           double endLat, double endLon,
                                           double intervalMeters,
                                           String apiKey) throws IOException {
        List<RoutePoint> pts = generateRoute(startLat, startLon, endLat, endLon, intervalMeters, apiKey);
        return gson.toJson(pts);
    }

    /**
     * Returns metrics (distance, duration and routeIndex) for the shortest route among alternatives.
     */
    public static RouteMetrics getShortestRouteMetrics(double startLat, double startLon,
                                                       double endLat, double endLon,
                                                       String apiKey) throws IOException {
        requireApiKey(apiKey);
        return RouteGeneratorCore.getShortestRouteMetrics(startLat, startLon, endLat, endLon, apiKey);
    }

    /**
     * NEW: Return metrics for all routes returned by Google and indicate which was chosen (shortest).
     * The returned object's routes list preserves the same order as Google returns them (index 0..n-1).
     */
    public static RouteComparisonResult getAllRouteMetrics(double startLat, double startLon,
                                                           double endLat, double endLon,
                                                           String apiKey) throws IOException {
        requireApiKey(apiKey);
        return RouteGeneratorCore.getAllRouteMetrics(startLat, startLon, endLat, endLon, apiKey);
    }

    // ---------- CLI (unchanged) ----------
    public static void main(String[] args) {
        // Keep CLI same as before: prints geometry JSON.
        if (args.length != 6) {
            System.err.println("Usage: java -jar route-service.jar <startLat> <startLon> <endLat> <endLon> <intervalMeters> <API_KEY>");
            System.exit(2);
        }
        try {
            double sLat = Double.parseDouble(args[0]);
            double sLon = Double.parseDouble(args[1]);
            double eLat = Double.parseDouble(args[2]);
            double eLon = Double.parseDouble(args[3]);
            double interval = Double.parseDouble(args[4]);
            String apiKey = args[5];
            String json = generateRouteJson(sLat, sLon, eLat, eLon, interval, apiKey);
            System.out.println(json);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(3);
        }
    }

    // ---------- Helpers ----------
    private static void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "Google API key missing. Provide via env GOOGLE_API_KEY or pass as argument.");
        }
    }

    // ---------- Internal core implementation ----------
    static class LatLng {
        final double lat;
        final double lon;
        LatLng(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    static class RouteGeneratorCore {
        private static final OkHttpClient client = new OkHttpClient();
        private static final double EARTH_R = 6_371_000.0;

        // Public internal entry used by library methods:
        static List<LatLng> generatePointsFromDirections(double startLat, double startLon,
                                                         double endLat, double endLon,
                                                         double intervalMeters,
                                                         String apiKey) throws IOException {
            String directionsJson = fetchDirections(startLat, startLon, endLat, endLon, apiKey);
            JsonObject root = JsonParser.parseString(directionsJson).getAsJsonObject();
            JsonArray routes = root.has("routes") ? root.getAsJsonArray("routes") : null;
            if (routes == null || routes.size() == 0) throw new IOException("Directions API returned no routes");

            // choose shortest route by distance
            int bestIndex = pickShortestRouteIndex(routes);
            JsonObject bestRoute = routes.get(bestIndex).getAsJsonObject();
            List<LatLng> stepPts = extractPointsFromRoute(bestRoute);
            if (stepPts.size() < 2) throw new IOException("Best route geometry too short");
            return densifyRoute(stepPts, intervalMeters);
        }

        // NEW: returns metrics for all routes and chosen shortest index
        static RouteComparisonResult getAllRouteMetrics(double startLat, double startLon,
                                                        double endLat, double endLon,
                                                        String apiKey) throws IOException {
            String directionsJson = fetchDirections(startLat, startLon, endLat, endLon, apiKey);
            JsonObject root = JsonParser.parseString(directionsJson).getAsJsonObject();
            JsonArray routes = root.has("routes") ? root.getAsJsonArray("routes") : null;
            if (routes == null || routes.size() == 0) throw new IOException("Directions API returned no routes");

            List<RouteMetrics> metricsList = new ArrayList<>();
            for (int i = 0; i < routes.size(); i++) {
                JsonObject route = routes.get(i).getAsJsonObject();
                double totalDist = 0.0;
                double totalDur = 0.0;
                boolean hasDist = true;
                boolean hasDur = true;

                JsonArray legs = route.has("legs") ? route.getAsJsonArray("legs") : null;
                if (legs != null) {
                    for (int li = 0; li < legs.size(); li++) {
                        JsonObject leg = legs.get(li).getAsJsonObject();
                        if (leg.has("distance") && leg.getAsJsonObject("distance").has("value")) {
                            totalDist += leg.getAsJsonObject("distance").get("value").getAsDouble();
                        } else {
                            hasDist = false;
                        }
                        if (leg.has("duration") && leg.getAsJsonObject("duration").has("value")) {
                            totalDur += leg.getAsJsonObject("duration").get("value").getAsDouble();
                        } else {
                            hasDur = false;
                        }
                    }
                } else {
                    hasDist = false; hasDur = false;
                }

                if (!hasDist) {
                    if (route.has("overview_polyline") && route.getAsJsonObject("overview_polyline").has("points")) {
                        String enc = route.getAsJsonObject("overview_polyline").get("points").getAsString();
                        List<LatLng> pts = decodePolyline(enc);
                        totalDist = computePolylineLengthMeters(pts);
                    } else {
                        totalDist = Double.POSITIVE_INFINITY;
                    }
                }
                if (!hasDur) totalDur = -1;

                metricsList.add(new RouteMetrics(totalDist, totalDur, i));
            }

            // pick shortest by distance (ties -> first)
            int chosen = 0;
            double minDist = Double.POSITIVE_INFINITY;
            for (RouteMetrics rm : metricsList) {
                if (rm.distanceMeters < minDist) {
                    minDist = rm.distanceMeters;
                    chosen = rm.routeIndex;
                }
            }

            return new RouteComparisonResult(metricsList, chosen);
        }

        // Helper used above to pick shortest index quickly
        private static int pickShortestRouteIndex(JsonArray routes) {
            int bestIndex = 0;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < routes.size(); i++) {
                JsonObject route = routes.get(i).getAsJsonObject();
                double totalDist = 0.0;
                boolean hasDist = true;
                JsonArray legs = route.has("legs") ? route.getAsJsonArray("legs") : null;
                if (legs != null) {
                    for (int li = 0; li < legs.size(); li++) {
                        JsonObject leg = legs.get(li).getAsJsonObject();
                        if (leg.has("distance") && leg.getAsJsonObject("distance").has("value")) {
                            totalDist += leg.getAsJsonObject("distance").get("value").getAsDouble();
                        } else {
                            hasDist = false;
                        }
                    }
                } else hasDist = false;

                if (!hasDist) {
                    if (route.has("overview_polyline") && route.getAsJsonObject("overview_polyline").has("points")) {
                        String enc = route.getAsJsonObject("overview_polyline").get("points").getAsString();
                        List<LatLng> pts = decodePolyline(enc);
                        totalDist = computePolylineLengthMeters(pts);
                    } else totalDist = Double.POSITIVE_INFINITY;
                }

                if (totalDist < bestDistance) {
                    bestDistance = totalDist;
                    bestIndex = i;
                }
            }
            return bestIndex;
        }

        // existing helper: returns RouteMetrics for the chosen route (shortest)
        static RouteService.RouteMetrics getShortestRouteMetrics(double startLat, double startLon,
                                                                 double endLat, double endLon,
                                                                 String apiKey) throws IOException {
            RouteComparisonResult res = getAllRouteMetrics(startLat, startLon, endLat, endLon, apiKey);
            RouteMetrics chosen = res.routes.get(res.chosenIndex);
            return chosen;
        }

        // HTTP call to Google Directions
        private static String fetchDirections(double sLat, double sLon, double eLat, double eLon, String apiKey) throws IOException {
            if (apiKey == null) throw new IllegalArgumentException("API key null");
            String url = buildDirectionsUrl(sLat, sLon, eLat, eLon, apiKey);
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    throw new IOException("Directions API error: HTTP " + resp.code() + " - " + body);
                }
                return resp.body().string();
            }
        }

        private static String buildDirectionsUrl(double sLat, double sLon, double eLat, double eLon, String apiKey) {
            String origin = sLat + "," + sLon;
            String dest = eLat + "," + eLon;
            String base = "https://maps.googleapis.com/maps/api/directions/json";
            String keyParam = "key=" + urlEncode(apiKey);
            return String.format("%s?origin=%s&destination=%s&mode=driving&alternatives=true&%s",
                    base, urlEncode(origin), urlEncode(dest), keyParam);
        }

        // defensive urlEncode
        private static String urlEncode(String s) {
            if (s == null) throw new IllegalArgumentException("urlEncode received null — missing required parameter (likely API key).");
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }

        // ---------- existing route geometry helpers ----------
        private static List<LatLng> extractPointsFromRoute(JsonObject route) {
            List<LatLng> all = new ArrayList<>();
            JsonArray legs = route.has("legs") ? route.getAsJsonArray("legs") : null;
            if (legs == null) return all;
            for (int li = 0; li < legs.size(); li++) {
                JsonObject leg = legs.get(li).getAsJsonObject();
                JsonArray steps = leg.has("steps") ? leg.getAsJsonArray("steps") : null;
                if (steps == null) continue;
                for (int si = 0; si < steps.size(); si++) {
                    JsonObject step = steps.get(si).getAsJsonObject();
                    if (!step.has("polyline")) continue;
                    JsonObject poly = step.getAsJsonObject("polyline");
                    if (!poly.has("points")) continue;
                    String enc = poly.get("points").getAsString();
                    List<LatLng> stepPts = decodePolyline(enc);
                    if (!all.isEmpty() && !stepPts.isEmpty()) {
                        LatLng last = all.get(all.size() - 1);
                        LatLng first = stepPts.get(0);
                        if (Math.abs(last.lat - first.lat) < 1e-9 && Math.abs(last.lon - first.lon) < 1e-9) {
                            for (int k = 1; k < stepPts.size(); k++) all.add(stepPts.get(k));
                        } else {
                            all.addAll(stepPts);
                        }
                    } else {
                        all.addAll(stepPts);
                    }
                }
            }
            // dedupe adjacents
            List<LatLng> dedup = new ArrayList<>();
            LatLng prev = null;
            for (LatLng p : all) {
                if (prev == null || Math.abs(prev.lat - p.lat) > 1e-9 || Math.abs(prev.lon - p.lon) > 1e-9) {
                    dedup.add(p);
                    prev = p;
                }
            }
            return dedup;
        }

        private static List<LatLng> decodePolyline(String encoded) {
            List<LatLng> poly = new ArrayList<>();
            int index = 0, len = encoded.length();
            int lat = 0, lng = 0;
            while (index < len) {
                int b, shift = 0, result = 0;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                int dlat = ((result & 1) != 0) ? ~(result >> 1) : (result >> 1);
                lat += dlat;

                shift = 0;
                result = 0;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                int dlng = ((result & 1) != 0) ? ~(result >> 1) : (result >> 1);
                lng += dlng;

                double latitude = lat / 1e5;
                double longitude = lng / 1e5;
                poly.add(new LatLng(latitude, longitude));
            }
            return poly;
        }

        private static List<LatLng> densifyRoute(List<LatLng> poly, double intervalMeters) {
            List<LatLng> out = new ArrayList<>();
            if (poly.isEmpty()) return out;
            out.add(poly.get(0));
            for (int i = 0; i < poly.size() - 1; i++) {
                LatLng a = poly.get(i);
                LatLng b = poly.get(i + 1);
                double segDist = haversine(a.lat, a.lon, b.lat, b.lon);
                if (segDist <= 0) {
                    out.add(b);
                    continue;
                }
                double bearing = initialBearing(a.lat, a.lon, b.lat, b.lon);
                int steps = (int) Math.floor(segDist / intervalMeters);
                for (int s = 1; s <= steps; s++) {
                    double d = s * intervalMeters;
                    if (d >= segDist) break;
                    LatLng p = destinationPoint(a.lat, a.lon, bearing, d);
                    out.add(p);
                }
                out.add(b);
            }
            // dedupe
            List<LatLng> dedup = new ArrayList<>();
            LatLng prev = null;
            for (LatLng p : out) {
                if (prev == null || Math.abs(prev.lat - p.lat) > 1e-9 || Math.abs(prev.lon - p.lon) > 1e-9) {
                    dedup.add(p);
                    prev = p;
                }
            }
            return dedup;
        }

        private static double computePolylineLengthMeters(List<LatLng> pts) {
            double total = 0.0;
            for (int i = 0; i < pts.size() - 1; i++) {
                total += haversine(pts.get(i).lat, pts.get(i).lon, pts.get(i + 1).lat, pts.get(i + 1).lon);
            }
            return total;
        }

        // geodesy helpers
        private static double haversine(double lat1, double lon1, double lat2, double lon2) {
            double φ1 = Math.toRadians(lat1);
            double φ2 = Math.toRadians(lat2);
            double Δφ = Math.toRadians(lat2 - lat1);
            double Δλ = Math.toRadians(lon2 - lon1);
            double a = Math.sin(Δφ / 2) * Math.sin(Δφ / 2)
                    + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) * Math.sin(Δλ / 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return EARTH_R * c;
        }

        private static double initialBearing(double lat1, double lon1, double lat2, double lon2) {
            double φ1 = Math.toRadians(lat1);
            double φ2 = Math.toRadians(lat2);
            double Δλ = Math.toRadians(lon2 - lon1);
            double y = Math.sin(Δλ) * Math.cos(φ2);
            double x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ);
            double θ = Math.toDegrees(Math.atan2(y, x));
            return (θ + 360) % 360;
        }

        private static LatLng destinationPoint(double lat1, double lon1, double bearingDeg, double distanceMeters) {
            double δ = distanceMeters / EARTH_R;
            double θ = Math.toRadians(bearingDeg);
            double φ1 = Math.toRadians(lat1);
            double λ1 = Math.toRadians(lon1);

            double φ2 = Math.asin(Math.sin(φ1) * Math.cos(δ) + Math.cos(φ1) * Math.sin(δ) * Math.cos(θ));
            double λ2 = λ1 + Math.atan2(Math.sin(θ) * Math.sin(δ) * Math.cos(φ1),
                    Math.cos(δ) - Math.sin(φ1) * Math.sin(φ2));
            double lat2 = Math.toDegrees(φ2);
            double lon2 = Math.toDegrees(λ2);
            lon2 = ((lon2 + 540) % 360) - 180;
            return new LatLng(lat2, lon2);
        }
    }
}
