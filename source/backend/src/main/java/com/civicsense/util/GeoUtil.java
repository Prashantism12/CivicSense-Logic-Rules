package com.civicsense.util;

public class GeoUtil {
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    public static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon/2)*Math.sin(dLon/2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    public static boolean isWithinRadius(double lat1, double lon1, double lat2, double lon2, double r) {
        return haversineDistance(lat1, lon1, lat2, lon2) <= r;
    }
}
