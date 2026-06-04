package com.teleonome.cerebellum.graveyardshift;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import com.teleonome.cerebellum.Cerebellum;
import com.teleonome.cerebellum.Task;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Projects the LoRa TX schedule and battery voltage for a Daffodil device
 * from tonight's sunset through to tomorrow's sunrise.
 *
 * Discharge rate is derived empirically from the device's own recent readings.
 * Sleep interval is taken from the most recent stored Sleep Time value.
 *
 * Standalone: java -jar Cerebellum.jar <deviceName>
 */
public class GraveyardShift implements Task {

    private static final String TIMEZONE = "Australia/Melbourne";
    private static final double FALLBACK_DISCHARGE_V_PER_HOUR = 0.04;

    private final String deviceName;

    public GraveyardShift(String deviceName) {
        this.deviceName = deviceName;
    }

    @Override
    public String getName() {
        return "GraveyardShift";
    }

    @Override
    public void run(Connection conn, MqttClient mqtt) throws Exception {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        String todayTable = getTableName(now);

        String device = deviceName;
        if (device == null) {
            device = pickDevice(conn, todayTable);
            if (device == null) {
                System.out.println("No devices found in " + todayTable);
                return;
            }
        }

        List<DeviceReading> readings = getRecentReadings(conn, todayTable, device, 40);
        if (readings.size() < 3) {
            Calendar yesterday = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
            yesterday.add(Calendar.DAY_OF_MONTH, -1);
            readings.addAll(getRecentReadings(conn, getTableName(yesterday), device, 20));
            readings.sort(Comparator.comparingLong(r -> -r.timeSeconds));
        }

        if (readings.isEmpty()) {
            System.out.println("No data found for device: " + device);
            return;
        }

        DeviceReading latest = readings.get(0);

        double lat = 0, lon = 0;
        for (DeviceReading r : readings) {
            if (r.latitude != 0) { lat = r.latitude; lon = r.longitude; break; }
        }
        if (lat == 0) {
            System.out.println("Could not determine lat/lon for " + device);
            return;
        }

        Location loc = new Location(String.valueOf(lat), String.valueOf(lon));
        SunriseSunsetCalculator calc = new SunriseSunsetCalculator(loc, TIMEZONE);

        Calendar sunsetCal  = calc.getOfficialSunsetCalendarForDate(now);
        Calendar tomorrow   = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        Calendar sunriseCal = calc.getOfficialSunriseCalendarForDate(tomorrow);

        long sunsetEpoch  = sunsetCal.getTimeInMillis()  / 1000;
        long sunriseEpoch = sunriseCal.getTimeInMillis() / 1000;

        double dischargeVPerHour = calcDischargeRate(readings);

        DeviceReading anchor = findLatestBefore(readings, sunsetEpoch);
        if (anchor == null) anchor = latest;
        double hoursAnchorToSunset = (sunsetEpoch - anchor.timeSeconds) / 3600.0;
        double voltageAtSunset = anchor.batteryVoltage - dischargeVPerHour * hoursAnchorToSunset;

        int sleepSec = latest.sleepTimeSec > 30 ? latest.sleepTimeSec : 900;

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm");
        timeFmt.setTimeZone(TimeZone.getTimeZone(TIMEZONE));
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        dateFmt.setTimeZone(TimeZone.getTimeZone(TIMEZONE));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf( "║  GRAVEYARD SHIFT  — %-41s║%n", device);
        System.out.printf( "║  %s sunset  →  %s sunrise%20s║%n",
                dateFmt.format(sunsetCal.getTime()),
                dateFmt.format(sunriseCal.getTime()), "");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf("  Sunset:       %s        Voltage at sunset:  %.3fV%n",
                timeFmt.format(sunsetCal.getTime()), voltageAtSunset);
        System.out.printf("  Sunrise:      %s        Discharge rate:     %.4f V/hr%n",
                timeFmt.format(sunriseCal.getTime()), dischargeVPerHour);
        System.out.printf("  Sleep period: %d s       Anchor reading:     %s (%.3fV)%n",
                sleepSec, timeFmt.format(new Date(anchor.timeSeconds * 1000L)), anchor.batteryVoltage);
        System.out.println();
        System.out.printf("  %-5s  %-6s  %-9s  %s%n", "TX#", "Time", "Voltage", "Notes");
        System.out.println("  ──────────────────────────────────────────────────");

        long txTime  = sunsetEpoch;
        double volts = voltageAtSunset;
        int txNum    = 0;

        while (txTime <= sunriseEpoch) {
            String note = "";
            if (txNum == 0) {
                note = "sunset";
            } else if (Math.abs(txTime - sunriseEpoch) <= sleepSec) {
                note = "→ sunrise";
            }

            Calendar txCal = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
            txCal.setTimeInMillis(txTime * 1000L);

            System.out.printf("  %-5d  %-6s  %7.3fV   %s%n",
                    txNum, timeFmt.format(txCal.getTime()), volts, note);

            txNum++;
            txTime += sleepSec;
            volts  -= dischargeVPerHour * (sleepSec / 3600.0);
        }

        double hoursOvernight   = (sunriseEpoch - sunsetEpoch) / 3600.0;
        double voltageAtSunrise = voltageAtSunset - dischargeVPerHour * hoursOvernight;
        System.out.println("  ──────────────────────────────────────────────────");
        System.out.printf("  Night duration:        %.1f hrs%n", hoursOvernight);
        System.out.printf("  Total TX cycles:       %d%n", txNum);
        System.out.printf("  Projected drop:        %.3fV%n", voltageAtSunset - voltageAtSunrise);
        System.out.printf("  Projected sunrise V:   %.3fV%n", voltageAtSunrise);
        System.out.println();
    }

    // ── Standalone entry point ────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String device = args.length > 0 ? args[0] : null;
        try (Connection conn = Cerebellum.connect()) {
            new GraveyardShift(device).run(conn, null);
        }
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private String getTableName(Calendar cal) {
        return "telepathon_" + cal.get(Calendar.YEAR) + "_"
                + (cal.get(Calendar.MONTH) + 1) + "_"
                + cal.get(Calendar.DAY_OF_MONTH);
    }

    private String pickDevice(Connection conn, String tableName) {
        String sql = "SELECT DISTINCT telepathonname FROM " + tableName + " ORDER BY telepathonname";
        System.out.println("Devices in " + tableName + ":");
        String first = null;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String n = rs.getString(1);
                System.out.println("  " + n);
                if (first == null) first = n;
            }
        } catch (SQLException e) {
            System.out.println("  (table not found)");
        }
        return first;
    }

    private List<DeviceReading> getRecentReadings(Connection conn, String table, String device, int limit) {
        List<DeviceReading> list = new ArrayList<>();
        String sql = "SELECT timeseconds, data FROM " + table
                + " WHERE telepathonname = ? ORDER BY timeseconds DESC LIMIT " + limit;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, device);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DeviceReading r = parseReading(rs.getLong("timeseconds"), rs.getString("data"));
                    if (r != null) list.add(r);
                }
            }
        } catch (SQLException ignored) {}
        return list;
    }

    private DeviceReading parseReading(long timeSeconds, String json) {
        try {
            JSONObject data = new JSONObject(json);
            DeviceReading r = new DeviceReading();
            r.timeSeconds    = timeSeconds;
            r.batteryVoltage = getDeneWordDouble(data, "Purpose",       "Battery Voltage");
            r.sleepTimeSec   = (int) getDeneWordDouble(data, "Purpose", "Sleep Time");
            r.latitude       = getDeneWordDouble(data, "Configuration", "Latitude");
            r.longitude      = getDeneWordDouble(data, "Configuration", "longitude"); // lowercase in source
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private double getDeneWordDouble(JSONObject data, String deneName, String wordName) {
        try {
            JSONArray denes = data.getJSONArray("Denes");
            for (int i = 0; i < denes.length(); i++) {
                JSONObject dene = denes.getJSONObject(i);
                if (deneName.equals(dene.getString("Name"))) {
                    JSONArray words = dene.getJSONArray("DeneWords");
                    for (int j = 0; j < words.length(); j++) {
                        JSONObject word = words.getJSONObject(j);
                        if (wordName.equals(word.getString("Name"))) {
                            return Double.parseDouble(word.getString("Value"));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    // ── Calculation helpers ───────────────────────────────────────────────────

    private double calcDischargeRate(List<DeviceReading> readings) {
        List<Double> rates = new ArrayList<>();
        for (int i = 0; i < readings.size() - 1; i++) {
            DeviceReading newer = readings.get(i);
            DeviceReading older = readings.get(i + 1);
            double dt = (newer.timeSeconds - older.timeSeconds) / 3600.0;
            if (dt <= 0) continue;
            double drop = older.batteryVoltage - newer.batteryVoltage;
            if (drop > 0 && drop < 0.8) {
                rates.add(drop / dt);
            }
        }
        if (rates.isEmpty()) {
            System.out.printf("  (no discharge pairs found — using fallback %.4f V/hr)%n",
                    FALLBACK_DISCHARGE_V_PER_HOUR);
            return FALLBACK_DISCHARGE_V_PER_HOUR;
        }
        return rates.stream().mapToDouble(v -> v).average().orElse(FALLBACK_DISCHARGE_V_PER_HOUR);
    }

    private DeviceReading findLatestBefore(List<DeviceReading> readings, long targetEpoch) {
        for (DeviceReading r : readings) {
            if (r.timeSeconds <= targetEpoch) return r;
        }
        return null;
    }

    // ── Data model ────────────────────────────────────────────────────────────

    private static class DeviceReading {
        long   timeSeconds;
        double batteryVoltage;
        int    sleepTimeSec;
        double latitude;
        double longitude;
    }
}
