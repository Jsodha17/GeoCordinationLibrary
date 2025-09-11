package com.example.routegen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * GoogleRouteGenerator - batch-capable
 *
 * Modes:
 *  - No args: interactive
 *  - 2 args: minimal (startLat,lon  endLat,lon)
 *  - 7 args: full (startLat startLon endLat endLon interval outJson outJs)
 *  - 1 arg pointing to a file: treat as batch input (CSV or .properties)
 *
 * Batch file examples (project root):
 *  - routes.csv  (recommended)
 *  - routes.properties (alternate)
 *
 * To run automatically during mvn package, see the suggested exec-maven-plugin addition in the pom.
 */
public class GoogleRouteGenerator {
    // Optional fallback key (leave blank if you prefer not to hardcode)
    private static final String GOOGLE_API_KEY = "AIzaSyCO4yYwqBOJsuDoD6zheIr5GeUcNOZVJzE";

    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final double EARTH_R = 6_371_000.0; // meters
    private static final String CONFIG_FILE = System.getProperty("user.home") + File.separator + ".routegen.properties";

    public static void main(String[] args) throws Exception {
        Properties cfg = loadConfig();

        // If single argument and it is a readable file -> batch mode
        if (args.length == 1 && new File(args[0]).isFile()) {
            File batchFile = new File(args[0]);
            System.out.println("Batch mode: reading routes from " + batchFile.getAbsolutePath());
            List<RouteSpec> routes = readBatchFile(batchFile);
            if (routes.isEmpty()) {
                System.out.println("No routes found in " + batchFile.getAbsolutePath());
                System.exit(0);
            }
            // Load API key once
            String apiKey = getApiKeyOrExit();
            for (RouteSpec r : routes) {
                System.out.printf("Processing route: %s -> %s (interval %.1f m) -> %s, %s%n",
                        r.startLat + "," + r.startLon, r.endLat + "," + r.endLon, r.intervalMeters, r.outJson, r.outJs);
                runSingleRoute(r, apiKey);
            }
            System.out.println("Batch processing complete.");
            return;
        }

        // Other modes: interactive, minimal, full as before
        if (args.length == 0) {
            interactiveMode(cfg);
            saveConfig(cfg);
            return;
        } else if (args.length == 2 && args[0].contains(",") && args[1].contains(",")) {
            double[] start = parseLatLonPair(args[0]);
            double[] end = parseLatLonPair(args[1]);
            double interval = Double.parseDouble(cfg.getProperty("intervalMeters", "10"));
            String outJson = defaultOutputName(cfg, "json");
            String outJs = defaultOutputName(cfg, "js");
            String apiKey = getApiKeyOrExit();
            runWithAllParams(start[0], start[1], end[0], end[1], interval, outJson, outJs, apiKey);
            cfg.setProperty("intervalMeters", String.valueOf(interval));
            cfg.setProperty("lastOutJson", outJson);
            cfg.setProperty("lastOutJs", outJs);
            saveConfig(cfg);
            return;
        } else if (args.length >= 7) {
            double startLat = Double.parseDouble(args[0]);
            double startLon = Double.parseDouble(args[1]);
            double endLat = Double.parseDouble(args[2]);
            double endLon = Double.parseDouble(args[3]);
            double interval = Double.parseDouble(args[4]);
            String outJson = args[5];
            String outJs = args[6];
            String apiKey = getApiKeyOrExit();
            runWithAllParams(startLat, startLon, endLat, endLon, interval, outJson, outJs, apiKey);
            cfg.setProperty("intervalMeters", String.valueOf(interval));
            cfg.setProperty("lastOutJson", outJson);
            cfg.setProperty("lastOutJs", outJs);
            saveConfig(cfg);
            return;
        } else {
            System.out.println("Invalid arguments. Use one of:");
            System.out.println("  java -jar route-generator.jar                (interactive)");
            System.out.println("  java -jar route-generator.jar startLat,lon endLat,lon   (minimal)");
            System.out.println("  java -jar route-generator.jar startLat startLon endLat endLon interval outJson outJs   (full)");
            System.out.println("  java -jar route-generator.jar routes.csv   (batch)");
            System.exit(1);
        }
    }

    // ---------- Batch file parsing ----------
    private static List<RouteSpec> readBatchFile(File f) throws IOException {
        String name = f.getName().toLowerCase();
        if (name.endsWith(".csv")) return readRoutesFromCsv(f);
        if (name.endsWith(".properties")) return readRoutesFromProperties(f);
        // try CSV parsing by default
        return readRoutesFromCsv(f);
    }

    private static List<RouteSpec> readRoutesFromCsv(File f) throws IOException {
        List<RouteSpec> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int ln = 0;
            while ((line = br.readLine()) != null) {
                ln++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // expected: startLat,startLon,endLat,endLon,interval,outJson,outJs
                String[] parts = line.split(",");
                if (parts.length < 7) {
                    System.err.println("Skipping line " + ln + " (invalid, expected 7 comma-separated values): " + line);
                    continue;
                }
                try {
                    double sLat = Double.parseDouble(parts[0].trim());
                    double sLon = Double.parseDouble(parts[1].trim());
                    double eLat = Double.parseDouble(parts[2].trim());
                    double eLon = Double.parseDouble(parts[3].trim());
                    double interval = Double.parseDouble(parts[4].trim());
                    String outJson = parts[5].trim();
                    String outJs = parts[6].trim();
                    out.add(new RouteSpec(sLat, sLon, eLat, eLon, interval, outJson, outJs));
                } catch (NumberFormatException ex) {
                    System.err.println("Skipping line " + ln + " (number parse error): " + line);
                }
            }
        }
        return out;
    }

    private static List<RouteSpec> readRoutesFromProperties(File f) throws IOException {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(f)) {
            p.load(fis);
        }
        List<RouteSpec> out = new ArrayList<>();
        // either route.count + route.1 ... OR route.<n>=csv line
        String countStr = p.getProperty("route.count");
        if (countStr != null) {
            int count = Integer.parseInt(countStr);
            for (int i = 1; i <= count; i++) {
                String line = p.getProperty("route." + i);
                if (line == null) continue;
                // parse same as CSV line
                String[] parts = line.split(",");
                if (parts.length < 7) continue;
                double sLat = Double.parseDouble(parts[0].trim());
                double sLon = Double.parseDouble(parts[1].trim());
                double eLat = Double.parseDouble(parts[2].trim());
                double eLon = Double.parseDouble(parts[3].trim());
                double interval = Double.parseDouble(parts[4].trim());
                String outJson = parts[5].trim();
                String outJs = parts[6].trim();
                out.add(new RouteSpec(sLat, sLon, eLat, eLon, interval, outJson, outJs));
            }
        } else {
            // fallback: try to find keys route.1, route.2... until missing
            int idx = 1;
            while (true) {
                String line = p.getProperty("route." + idx);
                if (line == null) break;
                String[] parts = line.split(",");
                if (parts.length >= 7) {
                    double sLat = Double.parseDouble(parts[0].trim());
                    double sLon = Double.parseDouble(parts[1].trim());
                    double eLat = Double.parseDouble(parts[2].trim());
                    double eLon = Double.parseDouble(parts[3].trim());
                    double interval = Double.parseDouble(parts[4].trim());
                    String outJson = parts[5].trim();
                    String outJs = parts[6].trim();
                    out.add(new RouteSpec(sLat, sLon, eLat, eLon, interval, outJson, outJs));
                }
                idx++;
            }
        }
        return out;
    }

    // ---------- helper to get API key ----------
    private static String getApiKeyOrExit() {
        String apiKey = System.getenv(GOOGLE_API_KEY);
        if (apiKey == null || apiKey.isEmpty()) apiKey = System.getProperty("google.api.key");
        if (apiKey == null || apiKey.isEmpty()) apiKey = GOOGLE_API_KEY;
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: Google API key not found. Set env GOOGLE_API_KEY or pass -Dgoogle.api.key=... or hard-code GOOGLE_API_KEY in source.");
            System.exit(10);
        }
        return apiKey;
    }

    // ---------- run one route spec (used by batch) ----------
    private static void runSingleRoute(RouteSpec r, String apiKey) throws Exception {
        runWithAllParams(r.startLat, r.startLon, r.endLat, r.endLon, r.intervalMeters, r.outJson, r.outJs, apiKey);
    }

    // ---------- existing interactive/minimal/full orchestration ----------
    private static void interactiveMode(Properties cfg) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Interactive mode - enter coordinates as: lat,lon  (e.g. 23.038765,72.610756)");
        String startInput = askWithDefault(br, "Start (lat,lon)", cfg.getProperty("lastStart", ""));
        String endInput = askWithDefault(br, "End (lat,lon)", cfg.getProperty("lastEnd", ""));
        double[] start = parseLatLonPair(startInput);
        double[] end = parseLatLonPair(endInput);

        String intervalStr = askWithDefault(br, "Interval meters", cfg.getProperty("intervalMeters", "10"));
        double interval = Double.parseDouble(intervalStr);

        String outJson = askWithDefault(br, "Output JSON filename", cfg.getProperty("lastOutJson", defaultOutputName(cfg, "json")));
        String outJs = askWithDefault(br, "Output JS filename", cfg.getProperty("lastOutJs", defaultOutputName(cfg, "js")));

        cfg.setProperty("lastStart", startInput);
        cfg.setProperty("lastEnd", endInput);
        cfg.setProperty("intervalMeters", String.valueOf(interval));
        cfg.setProperty("lastOutJson", outJson);
        cfg.setProperty("lastOutJs", outJs);

        String apiKey = getApiKeyOrExit();
        runWithAllParams(start[0], start[1], end[0], end[1], interval, outJson, outJs, apiKey);
    }

    private static String askWithDefault(BufferedReader br, String prompt, String defaultVal) throws IOException {
        if (defaultVal == null) defaultVal = "";
        if (defaultVal.isEmpty()) {
            System.out.print(prompt + ": ");
        } else {
            System.out.print(String.format("%s [%s]: ", prompt, defaultVal));
        }
        String line = br.readLine();
        if (line == null || line.trim().isEmpty()) return defaultVal;
        return line.trim();
    }

    private static double[] parseLatLonPair(String s) {
        String[] parts = s.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected lat,lon pair but got: " + s);
        }
        return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
    }

    private static String defaultOutputName(Properties cfg, String ext) {
        String last = cfg.getProperty("lastOut" + (ext.equals("json") ? "Json" : "Js"));
        if (last != null && !last.isEmpty()) return last;
        String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");
        return "route-" + ts + "." + ext;
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        File f = new File(CONFIG_FILE);
        if (!f.exists()) return p;
        try (FileInputStream fis = new FileInputStream(f)) {
            p.load(fis);
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
        }
        return p;
    }

    private static void saveConfig(Properties p) {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            p.store(fos, "route-generator config (last used values)");
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    // ---------- orchestrator for single route (chooses shortest among alternatives) ----------
    private static void runWithAllParams(double startLat, double startLon, double endLat, double endLon, double intervalMeters, String outJson, String outJs, String apiKey) throws Exception {
        System.out.printf("Generating route from %.6f,%.6f -> %.6f,%.6f (interval %.1f m)%n", startLat, startLon, endLat, endLon, intervalMeters);

        String directionsJson = fetchDirections(startLat, startLon, endLat, endLon, apiKey);
        if (directionsJson == null) {
            System.err.println("Failed to fetch directions.");
            return;
        }

        JsonObject root = JsonParser.parseString(directionsJson).getAsJsonObject();
        JsonArray routes = root.has("routes") ? root.getAsJsonArray("routes") : null;
        if (routes == null || routes.size() == 0) {
            System.err.println("No routes returned by Directions API.");
            return;
        }

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
                    String overviewEncoded = route.getAsJsonObject("overview_polyline").get("points").getAsString();
                    List<LatLng> overviewPts = decodePolyline(overviewEncoded);
                    totalDist = computePolylineLengthMeters(overviewPts);
                } else {
                    totalDist = Double.POSITIVE_INFINITY;
                }
            }
            System.out.printf("Route %d distance=%.1f m%n", i, totalDist);
            if (totalDist < bestDistance) {
                bestDistance = totalDist;
                bestIndex = i;
            }
        }

        if (bestIndex < 0) {
            System.err.println("Unable to select a best route.");
            return;
        }

        System.out.printf("Selected route index %d (distance=%.1f m)%n", bestIndex, bestDistance);
        JsonObject bestRoute = routes.get(bestIndex).getAsJsonObject();
        List<LatLng> pts = extractPointsFromRoute(bestRoute);
        if (pts.size() < 2) {
            System.err.println("Extracted route geometry too short.");
            return;
        }

        List<LatLng> densified = densifyRoute(pts, intervalMeters);
        writeJson(densified, outJson);
        writeJs(densified, outJs, "routePoints");
        System.out.printf("Wrote %d points -> %s and %s%n", densified.size(), outJson, outJs);
    }

    // ---------- Directions request ----------
    private static String fetchDirections(double sLat, double sLon, double eLat, double eLon, String apiKey) throws IOException {
        String url = buildDirectionsUrl(sLat, sLon, eLat, eLon, apiKey);
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("Directions API request failed: HTTP " + resp.code());
                if (resp.body() != null) System.err.println(resp.body().string());
                return null;
            }
            return resp.body().string();
        }
    }

    private static String buildDirectionsUrl(double sLat, double sLon, double eLat, double eLon, String apiKey) {
        String origin = sLat + "," + sLon;
        String dest = eLat + "," + eLon;
        String mode = "driving";
        String base = "https://maps.googleapis.com/maps/api/directions/json";
        String keyParam = "key=" + urlEncode(apiKey);
        return String.format("%s?origin=%s&destination=%s&mode=%s&alternatives=true&%s",
                base, urlEncode(origin), urlEncode(dest), mode, keyParam);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---------- Extract step-level points ----------
    private static List<LatLng> extractPointsFromRoute(JsonObject route) {
        List<LatLng> allPoints = new ArrayList<>();
        if (route == null) return allPoints;
        JsonArray legs = route.has("legs") ? route.getAsJsonArray("legs") : null;
        if (legs == null) return allPoints;
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
                if (!allPoints.isEmpty() && !stepPts.isEmpty()) {
                    LatLng last = allPoints.get(allPoints.size() - 1);
                    LatLng firstOfStep = stepPts.get(0);
                    if (Math.abs(last.lat - firstOfStep.lat) < 1e-9 && Math.abs(last.lon - firstOfStep.lon) < 1e-9) {
                        for (int k = 1; k < stepPts.size(); k++) allPoints.add(stepPts.get(k));
                    } else {
                        allPoints.addAll(stepPts);
                    }
                } else {
                    allPoints.addAll(stepPts);
                }
            }
        }
        List<LatLng> dedup = new ArrayList<>();
        LatLng prev = null;
        for (LatLng p : allPoints) {
            if (prev == null || Math.abs(prev.lat - p.lat) > 1e-9 || Math.abs(prev.lon - p.lon) > 1e-9) {
                dedup.add(p);
                prev = p;
            }
        }
        return dedup;
    }

    // ---------- Polyline decode ----------
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

    // ---------- Densify ----------
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

    // ---------- Geodesy ----------
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

    // ---------- Outputs ----------
    private static void writeJson(List<LatLng> pts, String filename) throws IOException {
        List<Map<String, Double>> arr = new ArrayList<>();
        for (LatLng p : pts) {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("lat", round6(p.lat));
            m.put("lon", round6(p.lon));
            arr.add(m);
        }
        try (FileWriter fw = new FileWriter(filename)) {
            gson.toJson(arr, fw);
        }
    }

    private static void writeJs(List<LatLng> pts, String filename, String varName) throws IOException {
        List<Map<String, Double>> arr = new ArrayList<>();
        for (LatLng p : pts) {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("lat", round6(p.lat));
            m.put("lon", round6(p.lon));
            arr.add(m);
        }
        String js = "// Auto-generated route points\nconst " + varName + " = " + gson.toJson(arr) + ";\n\nmodule.exports = " + varName + ";\n";
        Files.write(Paths.get(filename), js.getBytes(StandardCharsets.UTF_8));
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    private static class LatLng {
        final double lat;
        final double lon;
        LatLng(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    private static class RouteSpec {
        final double startLat, startLon, endLat, endLon;
        final double intervalMeters;
        final String outJson, outJs;
        RouteSpec(double sLat, double sLon, double eLat, double eLon, double interval, String outJson, String outJs) {
            this.startLat = sLat; this.startLon = sLon; this.endLat = eLat; this.endLon = eLon;
            this.intervalMeters = interval; this.outJson = outJson; this.outJs = outJs;
        }
    }
}
