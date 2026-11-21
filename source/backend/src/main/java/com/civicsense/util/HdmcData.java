package com.civicsense.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for Hubballi-Dharwad Municipal Corporation (HDMC)
 * wards, zones and map centres. Every dropdown (register, profile, report,
 * admin filters) and every server-side zone / coordinate lookup reads from here,
 * so a change made here shows up in all portals.
 *
 * Official site: http://www.hdmc.mrc.gov.in/
 */
public final class HdmcData {

    public static final String CORPORATION_NAME = "Hubballi-Dharwad Municipal Corporation";
    public static final String CORPORATION_SHORT = "HDMC";
    public static final String CORPORATION_URL = "http://www.hdmc.mrc.gov.in/";
    public static final String DEFAULT_WARD_LABEL = "Ward 32 \u2013 Vidyanagar / Shirur Park";

    /** Map centre of the twin city (between Hubballi and Dharwad). */
    public static final double CENTER_LAT = 15.4100;
    public static final double CENTER_LNG = 75.0700;

    public static final class Ward {
        private final int number;
        private final String locality;
        private final String city;     // Hubballi | Dharwad
        private final double lat;
        private final double lng;

        Ward(int number, String locality, String city, double lat, double lng) {
            this.number = number;
            this.locality = locality;
            this.city = city;
            this.lat = lat;
            this.lng = lng;
        }

        public int getNumber()      { return number; }
        public String getLocality() { return locality; }
        public String getCity()     { return city; }
        public String getZone()     { return city + " Zone"; }
        public double getLat()      { return lat; }
        public double getLng()      { return lng; }

        /** Value stored in the database / posted by the forms. */
        public String getLabel() {
            return "Ward " + number + " \u2013 " + locality;
        }
    }

    private static final List<Ward> WARDS;

    static {
        List<Ward> w = new ArrayList<>();
        // ── 10 wards of Dharwad ──
        w.add(new Ward(1,  "Vidyagiri / Kalyan Nagar",              "Dharwad", 15.4830, 75.0140));
        w.add(new Ward(2,  "Navalur / Sattur",                      "Dharwad", 15.4650, 74.9930));
        w.add(new Ward(8,  "Hebballi Agasi / Murugharajendra Nagar","Dharwad", 15.4720, 75.0300));
        w.add(new Ward(9,  "Koli Keri / Joshi Colony",              "Dharwad", 15.4580, 75.0080));
        w.add(new Ward(10, "Hosayallapur / Madaramaddi",            "Dharwad", 15.4520, 75.0020));
        w.add(new Ward(11, "Bhusappa Chowk / Rasulpur",             "Dharwad", 15.4610, 75.0110));
        w.add(new Ward(12, "Saptapur / Malamaddi",                  "Dharwad", 15.4470, 74.9880));
        w.add(new Ward(15, "Narayanpur / Saraswatpur",              "Dharwad", 15.4420, 75.0050));
        w.add(new Ward(21, "Saidapur / CB Nagar",                   "Dharwad", 15.4380, 75.0190));
        w.add(new Ward(25, "Lakshmisingankere / Gulganjikop",       "Dharwad", 15.4300, 75.0250));
        // ── 10 wards of Hubballi ──
        w.add(new Ward(29, "Ashok Nagar / Lingaraj Nagar",          "Hubballi", 15.3560, 75.1330));
        w.add(new Ward(32, "Vidyanagar / Shirur Park",              "Hubballi", 15.3700, 75.1100));
        w.add(new Ward(37, "Keshwapur / Shanti Nagar",              "Hubballi", 15.3600, 75.1150));
        w.add(new Ward(42, "Gopankoppa / Badami Nagar",             "Hubballi", 15.3450, 75.1150));
        w.add(new Ward(46, "Dajiban Peth / Coen Road",              "Hubballi", 15.3510, 75.1240));
        w.add(new Ward(50, "Kamripeth / Shahabazar",                "Hubballi", 15.3480, 75.1330));
        w.add(new Ward(55, "Old Hubli / Karwar Road areas",         "Hubballi", 15.3430, 75.1390));
        w.add(new Ward(62, "Anand Nagar / Nekar Nagar",             "Hubballi", 15.3300, 75.1230));
        w.add(new Ward(70, "Mantur Road / Chetana Colony",          "Hubballi", 15.3950, 75.1150));
        w.add(new Ward(82, "Tarihal Industrial Area / Gabbur outskirts", "Hubballi", 15.3880, 75.0840));
        WARDS = Collections.unmodifiableList(w);
    }

    private HdmcData() {}

    public static List<Ward> getWards() { return WARDS; }

    public static List<Ward> getDharwadWards() {
        List<Ward> r = new ArrayList<>();
        for (Ward w : WARDS) if ("Dharwad".equals(w.getCity())) r.add(w);
        return r;
    }

    public static List<Ward> getHubballiWards() {
        List<Ward> r = new ArrayList<>();
        for (Ward w : WARDS) if ("Hubballi".equals(w.getCity())) r.add(w);
        return r;
    }

    /** Find a ward from the stored label, "Ward 32", "32", or any part of the locality text. */
    public static Ward find(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        for (Ward w : WARDS) if (w.getLabel().equalsIgnoreCase(t)) return w;
        // "Ward No. 32" / "Ward 32" / "32"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^(?:ward\\s*(?:no\\.?)?\\s*)?(\\d{1,3})\\b").matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            for (Ward w : WARDS) if (w.getNumber() == n) return w;
        }
        String lower = t.toLowerCase();
        for (Ward w : WARDS) {
            for (String part : w.getLocality().toLowerCase().split("\\s*/\\s*")) {
                if (!part.isEmpty() && (lower.contains(part) || part.contains(lower))) return w;
            }
        }
        return null;
    }

    public static boolean isValidWard(String text) {
        if (text == null) return false;
        for (Ward w : WARDS) if (w.getLabel().equals(text.trim())) return true;
        return false;
    }

    public static String zoneFor(String wardText) {
        Ward w = find(wardText);
        return w != null ? w.getZone() : "Hubballi Zone";
    }
}
