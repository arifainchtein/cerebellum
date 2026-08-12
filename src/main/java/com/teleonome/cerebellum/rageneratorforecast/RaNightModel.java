package com.teleonome.cerebellum.rageneratorforecast;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import com.teleonome.framework.persistence.PostgresqlPersistenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Shared sunset/sunrise and historical-night-load math for Ra's battery
 * forecasting - used by both the live per-pulse RaGeneratorForecastTask and the
 * on-demand RaGeneratorRunSimulator CLI tool, so a change to either the location
 * or the historical-averaging approach only has to happen in one place.
 */
final class RaNightModel {

    static final double BATTERY_CAPACITY_AH = 660.0;
    static final int    NIGHTS_TO_AVERAGE    = 5;

    // Ra's location - not stored in the denome (unlike Daffodil-style devices'
    // Configuration:Latitude/longitude), given directly by Ari. Altitude (~400m)
    // isn't a SunriseSunsetCalculator input - at 400m that's a ~2-3 minute effect,
    // not worth a custom calculation for.
    private static final String LATITUDE  = "-37";
    private static final String LONGITUDE = "142";

    static final String TIMEZONE = "Australia/Melbourne";

    private RaNightModel() {}

    static long sunsetMillisForDate(Calendar date) {
        return new SunriseSunsetCalculator(new Location(LATITUDE, LONGITUDE), TIMEZONE)
                .getOfficialSunsetCalendarForDate(date).getTimeInMillis();
    }

    static long sunriseMillisForDate(Calendar date) {
        return new SunriseSunsetCalculator(new Location(LATITUDE, LONGITUDE), TIMEZONE)
                .getOfficialSunriseCalendarForDate(date).getTimeInMillis();
    }

    static Calendar addDays(Calendar cal, int days) {
        Calendar copy = (Calendar) cal.clone();
        copy.add(Calendar.DAY_OF_YEAR, days);
        return copy;
    }

    /** The next sunrise not yet passed - today's if we're before it, otherwise tomorrow's. */
    static long nextSunriseMillis(long nowMillis) {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        now.setTimeInMillis(nowMillis);
        long todaySunrise = sunriseMillisForDate(now);
        if (nowMillis < todaySunrise) return todaySunrise;
        return sunriseMillisForDate(addDays(now, 1));
    }

    /**
     * Average net Amps (Charge - Load) over the sunset->sunrise window on each of
     * the last NIGHTS_TO_AVERAGE nights, flattened into one average rather than
     * paired per-timestamp - Charge is normally ~0 overnight anyway (outside a
     * generator run), so the small loss of precision from averaging Charge and
     * Load independently doesn't matter.
     */
    static double averageHistoricalNightNetAmps(PostgresqlPersistenceManager db, long nowMillis) {
        double loadSum = 0, chargeSum = 0;
        int loadCount = 0, chargeCount = 0;

        Calendar day = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        day.setTimeInMillis(nowMillis);

        for (int i = 1; i <= NIGHTS_TO_AVERAGE; i++) {
            Calendar nightStart = (Calendar) day.clone();
            nightStart.add(Calendar.DAY_OF_YEAR, -i);
            long sunsetMillis  = sunsetMillisForDate(nightStart);
            long sunriseMillis = sunriseMillisForDate(addDays(nightStart, 1));
            if (sunriseMillis <= sunsetMillis) continue;

            JSONArray loadHistory = db.getRemeberedDeneWordStart(
                    "@Ra:Purpose:Sensor Data:Now:Load", sunsetMillis, sunriseMillis);
            for (int j = 0; j < loadHistory.length(); j++) {
                loadSum += loadHistory.getJSONObject(j).getDouble("Value");
                loadCount++;
            }

            JSONArray chargeHistory = db.getRemeberedDeneWordStart(
                    "@Ra:Purpose:Sensor Data:Now:Charge", sunsetMillis, sunriseMillis);
            for (int j = 0; j < chargeHistory.length(); j++) {
                chargeSum += chargeHistory.getJSONObject(j).getDouble("Value");
                chargeCount++;
            }
        }

        if (loadCount == 0) return 0;
        double avgLoad   = loadSum / loadCount;
        double avgCharge = chargeCount == 0 ? 0 : chargeSum / chargeCount;
        return avgCharge - avgLoad;
    }
}
