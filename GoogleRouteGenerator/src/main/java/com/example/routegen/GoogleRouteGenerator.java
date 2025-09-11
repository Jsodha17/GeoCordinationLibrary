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
 * Public API (library use):
 *   List<RouteService.RoutePoint> pts = RouteService.generateRoute(startLat, startLon, endLat, endLon, intervalMeters, apiKey);
 *   String json = RouteService.generateRouteJson(...);
 *
 * CLI usage (executable jar):
 *   java -jar route-service.jar <startLat> <startLon> <endLat> <endLon> <intervalMeters> <API_KEY>
 *   -> prints JSON array to stdout: [{"lat":..., "lon":...}, ...]
 */
public class RouteService {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * DTO returned to callers.
     */
    public static class RoutePoint {
        public final double lat;
        public final double lon;
        public RoutePoint(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    /**
     * Public library method - synchronous.
     *
     * @param startLat start latitude
     * @param startLon start longitude
     * @param endLat end latitude
     * @param endLon end longitude
     * @param intervalMeters densification interval in meters (e.g., 10)
     * @param apiKey Google Directions API key (required)
     * @return list of RoutePoint (lat/lon) along the chosen route (shortest by distance)
     * @throws IOException on networking/parsing errors
     */
    public static List<RoutePoint> generateRoute(double startLat, double startLon,
                                                 double endLat, double endLon,
                                                 double intervalMeters,
                                                 String apiKey) throws IOException {
        List<LatLng> pts = RouteGeneratorCore.generatePointsFromDirections(startLat, startLon, endLat, endLon, intervalMeters, apiKey);
        return pts.stream().map(p -> new RoutePoint(p.lat, p.lon)).collect(Collectors.toList());
    }

    /**
     * Convenience: JSON string of the list (pretty-printed).
     */
    public static String generateRouteJson(double startLat, double startLon,
                                           double endLat, double endLon,
                                           double intervalMeters,
                                           String apiKey) throws IOException {
        List<RoutePoint> pts = generateRoute(startLat, startLon, endLat, endLon, intervalMeters, apiKey);
        return gson.toJson(pts);
    }

    // ----------------- CLI entrypoint -----------------
    public static void main(String[] args) {
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

    // ----------------- Internal core implementation -----------------
    // All internal code left package-private / private to avoid polluting public API.
    static class LatLng {
        final double lat;
        final double lon;
        LatLng(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    static class RouteGeneratorCore {
        private static final OkHttpClient client = new OkHttpClient();
        private static final double EARTH_R = 6_371_000.0;

        /**
         * Call Google Directions (alternatives=true), pick the shortest route by distance,
         * extract step-level polylines, densify to intervalMeters, and return list of LatLng.
         */
        static List<LatLng> generatePointsFromDirections(double startLat, double startLon,
                                                         double endLat, double endLon,
                                                         double intervalMeters,
                                                         String apiKey) throws IOException {
            String directionsJson = fetchDirections(startLat, startLon, endLat, endLon, apiKey);
            if (directionsJson == null) throw new IOException("No response from Directions API");

            JsonObject root = JsonParser.parseString(directionsJson).getAsJsonObject();
            JsonArray routes = root.has("routes") ? root.getAsJsonArray("routes") : null;
            if (routes == null || routes.size() == 0) throw new IOException("Directions API returned no routes");

            int bestIndex = -1;
            double bestDistance = Double.POSITIVE_INFINITY;

            for (int i = 0; i < routes.size(); i++) {
                JsonObject route = routes.get(i).getAsJsonObject();
                JsonArray legs = route.has("legs") ? route.getAsJsonArray("legs") : null;
                double totalDist = 0.0;
                boolean hasDistance = true;
                if (legs != null) {
                    for (int li = 0; li < legs.size(); li++) {
                        JsonObject leg = legs.get(li).getAsJsonObject();
                        if (leg.has("distance") && leg.getAsJsonObject("distance").has("value")) {
                            totalDist += leg.getAsJsonObject("distance").get("value").getAsDouble();
                        } else {
                            hasDistance = false;
                        }
                    }
                } else hasDistance = false;

                if (!hasDistance) {
                    if (route.has("overview_polyline") && route.getAsJsonObject("overview_polyline").has("points")) {
                        String overviewEnc = route.getAsJsonObject("overview_polyline").get("points").getAsString();
                        List<LatLng> pts = decodePolyline(overviewEnc);
                        totalDist = computePolylineLengthMeters(pts);
                    } else {
                        totalDist = Double.POSITIVE_INFINITY;
                    }
                }

                if (totalDist < bestDistance) {
                    bestDistance = totalDist;
                    bestIndex = i;
                }
            }

            if (bestIndex < 0) throw new IOException("Could not choose best route");

            JsonObject bestRoute = routes.get(bestIndex).getAsJsonObject();
            List<LatLng> stepPts = extractPointsFromRoute(bestRoute);
            if (stepPts.size() < 2) throw new IOException("Best route geometry too short");

            return densifyRoute(stepPts, intervalMeters);
        }

        // HTTP call
        private static String fetchDirections(double sLat, double sLon, double eLat, double eLon, String apiKey) throws IOException {
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

        private static String urlEncode(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }

        // Extract step-level polylines -> ordered LatLng list
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
            // final dedupe adjacent
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

        // decode Google polyline
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

        // densify each segment with points every intervalMeters
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
            // dedupe adjacent near-equal
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

        // geodesy helpers (spherical approx)
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
