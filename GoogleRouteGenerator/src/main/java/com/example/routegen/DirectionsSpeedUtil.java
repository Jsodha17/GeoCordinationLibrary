package com.example.routegen;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for computing average speeds from a Google Directions API route JSONObject.
 * Usage:
 *   List<DirectionsSpeedUtil.SpeedInfo> speeds = DirectionsSpeedUtil.processSpeedsForRoute(routeJson);
 * Call this right after you obtain a single 'route' JSONObject from the Directions response:
 *   JSONObject directions = ...;
 *   JSONArray routes = directions.getJSONArray("routes");
 *   JSONObject route = routes.getJSONObject(0);
 *   List<SpeedInfo> speeds = DirectionsSpeedUtil.processSpeedsForRoute(route);
 */
public class DirectionsSpeedUtil {

    /**
     * Simple POJO to hold speed results for convenience.
     */
    public static class SpeedInfo {
        public final double metersPerSecond;
        public final double kilometersPerHour;
        public final String id; // e.g., "leg-0" or "leg-0-step-2"
        public final double distanceMeters;
        public final double durationSeconds;

        public SpeedInfo(String id, double mps, double distanceMeters, double durationSeconds) {
            this.id = id;
            this.metersPerSecond = mps;
            this.kilometersPerHour = mps * 3.6;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }

        @Override
        public String toString() {
            return id + ": " +
                    String.format("%.3f m/s (%.2f km/h)", metersPerSecond, kilometersPerHour) +
                    "  | distance=" + (int)distanceMeters + "m" +
                    "  | duration=" + (int)durationSeconds + "s";
        }
    }

    /**
     * Compute average speed in m/s. Defensive against zero duration.
     */
    public static double computeSpeedMps(double distanceMeters, double durationSeconds) {
        if (durationSeconds <= 0.0) return 0.0;
        return distanceMeters / durationSeconds;
    }

    /**
     * Parse a single route JSONObject and return speeds for legs and steps.
     * Call this RIGHT AFTER you obtain a `route` JSONObject from Directions API.
     * Example insertion point in your code:
     *   JSONObject route = routes.getJSONObject(r);
     *   List<SpeedInfo> speeds = DirectionsSpeedUtil.processSpeedsForRoute(route);
     */
    public static List<SpeedInfo> processSpeedsForRoute(JSONObject routeJson) throws JSONException {
        List<SpeedInfo> result = new ArrayList<>();

        JSONArray legs = routeJson.optJSONArray("legs");
        if (legs == null) return result;

        for (int li = 0; li < legs.length(); li++) {
            JSONObject leg = legs.getJSONObject(li);

            // leg-level distance (meters)
            double legDistance = leg.has("distance") ? leg.getJSONObject("distance").optDouble("value", 0.0) : 0.0;

            // prefer duration_in_traffic if present, else duration
            double legDuration = 0.0;
            if (leg.has("duration_in_traffic")) {
                legDuration = leg.getJSONObject("duration_in_traffic").optDouble("value", 0.0);
            } else if (leg.has("duration")) {
                legDuration = leg.getJSONObject("duration").optDouble("value", 0.0);
            }

            double legSpeedMps = computeSpeedMps(legDistance, legDuration);
            result.add(new SpeedInfo("leg-" + li, legSpeedMps, legDistance, legDuration));

            // steps inside this leg
            JSONArray steps = leg.optJSONArray("steps");
            if (steps == null) continue;

            for (int si = 0; si < steps.length(); si++) {
                JSONObject step = steps.getJSONObject(si);
                double stepDistance = step.has("distance") ? step.getJSONObject("distance").optDouble("value", 0.0) : 0.0;

                // For steps: Google Directions typically supplies 'duration' per step.
                // There is no duration_in_traffic on steps in many responses; handle gracefully.
                double stepDuration = 0.0;
                if (step.has("duration_in_traffic")) {
                    stepDuration = step.getJSONObject("duration_in_traffic").optDouble("value", 0.0);
                } else if (step.has("duration")) {
                    stepDuration = step.getJSONObject("duration").optDouble("value", 0.0);
                } else {
                    // fallback: distribute leg duration proportionally by distance
                    if (legDistance > 0.0) {
                        stepDuration = (stepDistance / legDistance) * legDuration;
                    }
                }

                double stepSpeedMps = computeSpeedMps(stepDistance, stepDuration);
                result.add(new SpeedInfo("leg-" + li + "-step-" + si, stepSpeedMps, stepDistance, stepDuration));
            }
        }

        return result;
    }

    /**
     * Convenience printer: prints SpeedInfo list to stdout.
     */
    public static void printSpeeds(List<SpeedInfo> speeds) {
        if (speeds == null || speeds.isEmpty()) {
            System.out.println("No speed info available.");
            return;
        }
        for (SpeedInfo s : speeds) {
            System.out.println(s.toString());
        }
    }
}