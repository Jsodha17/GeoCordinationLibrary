package com.example.routegen;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * QuickTest to demonstrate DirectionsSpeedUtil usage.
 * Run:
 *   1) No args -> runs with a small mocked Directions JSON to show output quickly.
 *   2) With a file path arg -> loads the file content and parses it as Directions JSON.
 * Example:
 *   java QuickTest /path/to/directions_response.json
 */
public class QuickTest {

    public static void main(String[] args) {
        try {
            JSONObject directionsJson;

            if (args.length >= 1) {
                String filePath = args[0];
                // Use InputStreamReader with UTF-8 to correctly decode the file
                try (InputStream is = new FileInputStream(filePath);
                     InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    directionsJson = new JSONObject(new JSONTokener(reader));
                }
            } else {
                // Use the mock JSON string directly (don't convert to bytes when feeding JSONTokener)
                String mockJson = buildMockDirectionsJson();
                directionsJson = new JSONObject(mockJson);
                System.out.println("Using embedded mock Directions JSON (no file provided).");
            }

            JSONArray routes = directionsJson.optJSONArray("routes");
            if (routes == null || routes.isEmpty()) {
                System.out.println("No routes found in the provided JSON.");
                return;
            }

            // For demo, we pick route index 0. Change index if you want another route.
            JSONObject route = routes.getJSONObject(0);

            // Compute speeds for this route
            List<DirectionsSpeedUtil.SpeedInfo> speeds = DirectionsSpeedUtil.processSpeedsForRoute(route);

            System.out.println("=== Speeds for route[0] ===");
            DirectionsSpeedUtil.printSpeeds(speeds);

            // Example: print only leg speeds (filter)
            System.out.println("\n=== Leg-only speeds ===");
            for (DirectionsSpeedUtil.SpeedInfo si : speeds) {
                if (si.id.startsWith("leg-") && !si.id.contains("-step-")) {
                    System.out.println(si);
                }
            }

        } catch (Exception ex) {
            System.err.println("Error in QuickTest: " + ex.getMessage());
            ex.printStackTrace(System.err);
        }
    }

    /**
     * Small mock JSON builder with one route -> one leg -> two steps.
     * Distances in meters, durations in seconds.
     */
    private static String buildMockDirectionsJson() {
        return "{\n" +
                "  \"routes\": [\n" +
                "    {\n" +
                "      \"summary\": \"Mock route\",\n" +
                "      \"legs\": [\n" +
                "        {\n" +
                "          \"distance\": { \"text\": \"1.2 km\", \"value\": 1200 },\n" +
                "          \"duration\": { \"text\": \"10 mins\", \"value\": 600 },\n" +
                "          \"duration_in_traffic\": { \"text\": \"12 mins\", \"value\": 720 },\n" +
                "          \"steps\": [\n" +
                "            {\n" +
                "              \"distance\": { \"text\": \"600 m\", \"value\": 600 },\n" +
                "              \"duration\": { \"text\": \"5 mins\", \"value\": 300 }\n" +
                "            },\n" +
                "            {\n" +
                "              \"distance\": { \"text\": \"600 m\", \"value\": 600 },\n" +
                "              \"duration\": { \"text\": \"5 mins\", \"value\": 300 }\n" +
                "            }\n" +
                "          ]\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n";
    }
}
