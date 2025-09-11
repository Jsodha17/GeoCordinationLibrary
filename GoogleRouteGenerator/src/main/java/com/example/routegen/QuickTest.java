 package com.example.routegen;

import com.example.routegen.RouteService;

public class QuickTest {
    public static void main(String[] args) throws Exception {
        // 1) prefer env var
        String apiKey = "";

        RouteService.RouteComparisonResult comp = RouteService.getAllRouteMetrics(
                23.038765, 72.610756, 23.070000, 72.620000, apiKey
        );

        System.out.println("Total routes returned: " + comp.totalRoutes);
        for (RouteService.RouteMetrics m : comp.routes) {
            System.out.printf("Route %d -> distance=%.1f m, duration=%.1f s%n", m.routeIndex, m.distanceMeters, m.durationSeconds);
        }
        RouteService.RouteMetrics chosen = comp.routes.get(comp.chosenIndex);
        System.out.printf("Chosen (shortest) route index=%d distance=%.1f m duration=%.1f s%n",
                comp.chosenIndex, chosen.distanceMeters, chosen.durationSeconds);
    }
}
