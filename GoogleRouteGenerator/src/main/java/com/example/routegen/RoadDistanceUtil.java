package com.example.routegen;

import com.google.maps.GeoApiContext;
import com.google.maps.DistanceMatrixApi;
import com.google.maps.errors.ApiException;
import com.google.maps.model.DistanceMatrix;
import com.google.maps.model.DistanceMatrixElement;
import com.google.maps.model.LatLng;

import java.io.IOException;
import java.util.Date;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility to compute road distances (km) from one origin to many destination coordinates
 * using Google Maps Distance Matrix API.
 *
 * Note: enable "Distance Matrix API" (or Routes) and set billing on your Google Cloud project.
 */
public class RoadDistanceUtil {

    private final GeoApiContext ctx;
    // Google Distance Matrix limits: up to 25 origins OR 25 destinations per request, max 100 elements.
    private static final int MAX_DESTINATIONS_PER_REQUEST = 25;

    public RoadDistanceUtil(String apiKey) {
        ctx = new GeoApiContext.Builder()
                .apiKey(apiKey)
                .build();
    }

    /**
     * Compute road distances (kilometres) from single origin to multiple destinations.
     *
     * @param originLat origin latitude
     * @param originLng origin longitude
     * @param destinations list of destination pairs [lat,lng]
     * @param mode travel mode: "driving","walking","bicycling" (use driving for road distance)
     * @param trafficAware whether to include current traffic in travel time (doesn't change distance but may affect route)
     * @return map from destination (as "lat,lng" string) to distance in kilometers (Double). null value => no route.
     * @throws InterruptedException, IOException, ApiException
     */
    public Map<String, Double> distancesFromOrigin(double originLat,
                                                   double originLng,
                                                   List<double[]> destinations,
                                                   String mode,
                                                   boolean trafficAware)
            throws InterruptedException, ApiException, IOException {

        Map<String, Double> results = new LinkedHashMap<>();

        // Prepare origin as single LatLng
        LatLng origin = new LatLng(originLat, originLng);

        // Batch destinations into chunks of MAX_DESTINATIONS_PER_REQUEST
        for (int start = 0; start < destinations.size(); start += MAX_DESTINATIONS_PER_REQUEST) {
            int end = Math.min(start + MAX_DESTINATIONS_PER_REQUEST, destinations.size());
            List<double[]> chunk = destinations.subList(start, end);

            LatLng[] destLatLngs = chunk.stream()
                    .map(d -> new LatLng(d[0], d[1]))
                    .toArray(LatLng[]::new);

            // Build request
            com.google.maps.DistanceMatrixApiRequest req = DistanceMatrixApi.newRequest(ctx)
                    .origins(origin)
                    .destinations(destLatLngs)
                    .mode(com.google.maps.model.TravelMode.valueOf(mode.toUpperCase()))
                    .units(com.google.maps.model.Unit.METRIC);

            // For traffic-aware routing/time (distance unaffected but routes might differ) set departure_time=now
            if (trafficAware && "DRIVING".equalsIgnoreCase(mode)) {
                req.departureTime(new Date().toInstant());
            }

            DistanceMatrix matrix = req.await();

            // single origin => matrix.rows.length == 1
            if (matrix.rows == null || matrix.rows.length == 0) {
                // mark all chunk as null/unreachable
                for (double[] d : chunk) {
                    results.put(formatCoord(d), null);
                }
                continue;
            }

            DistanceMatrixElement[] elements = matrix.rows[0].elements;
            for (int i = 0; i < elements.length; i++) {
                DistanceMatrixElement el = elements[i];
                String key = formatCoord(chunk.get(i));
                if (el.status == com.google.maps.model.DistanceMatrixElementStatus.OK && el.distance != null) {
                    // distance in meters => convert to km
                    double km = el.distance.inMeters / 1000.0;
                    results.put(key, km);
                } else {
                    results.put(key, null); // not reachable or error
                }
            }
        }

        return results;
    }

    private String formatCoord(double[] d) {
        return String.format(Locale.ROOT, "%.6f,%.6f", d[0], d[1]);
    }

    public void shutdown() {
        ctx.shutdown();
    }

    // Example usage
    public static void main(String[] args) throws Exception {
        String apiKey = "AIzaSyCO4yYwqBOJsuDoD6zheIr5GeUcNOZVJzE";

        RoadDistanceUtil util = new RoadDistanceUtil(apiKey);

        double originLat = 23.064896; // Ahmedabad example
        double originLng = 72.556962;

        List<double[]> dests = Arrays.asList(
                new double[]{23.102007, 72.685498},
                new double[]{23.032407, 72.584466}
//                new double[]{22.9999, 72.6300}
        );

        Map<String, Double> res = util.distancesFromOrigin(originLat, originLng, dests, "driving", false);

        res.forEach((k, v) -> {
            if (v == null) System.out.println(k + " => unreachable / no route");
            else System.out.printf("%s => %.3f km%n", k, v);
        });

        util.shutdown();
    }
}