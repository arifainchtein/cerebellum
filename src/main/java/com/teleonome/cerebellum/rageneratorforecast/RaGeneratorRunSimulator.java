package com.teleonome.cerebellum.rageneratorforecast;

import com.teleonome.framework.TeleonomeConstants;
import com.teleonome.framework.denome.DenomeUtils;
import com.teleonome.framework.denome.Identity;
import com.teleonome.framework.persistence.PostgresqlPersistenceManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

import static com.teleonome.cerebellum.rageneratorforecast.RaNightModel.*;

/**
 * Purpose: on-demand "what if" tool - answers "if I run the generator for N
 * hours starting at time T, what SoC% will the bank be at by sunrise?" Standalone
 * main() (same pattern as GraveyardShift/LangleySilenceSweep in this repo)
 * rather than a live Cerebellum Task, because the scenario (when you start it,
 * for how long) is a decision only Ari makes in the moment - a per-pulse
 * background task can't know it in advance the way RaGeneratorForecastTask's
 * passive monitoring can.
 *
 * Splits the time from now to sunrise into up to three phases and sums their Ah
 * contribution against the SP PRO's current State of Charge:
 *   1. Before the generator starts (0 length if starting now): historical
 *      average night net Amps (see RaNightModel), same basis as
 *      RaGeneratorForecastTask's Layer 2.
 *   2. While the generator runs: a three-stage charge-current profile (spike ->
 *      bulk plateau -> taper) fitted from one observed run on Ra (2026-08-06,
 *      05:58-07:14: spiked to 122A, plateaued ~90-100A for ~35min, tapered to
 *      ~28A over the next ~30min before being switched off). Only one episode's
 *      worth of data, so treat this as a reasonable first approximation, not a
 *      calibrated model - it should improve as more runs get logged. While the
 *      generator runs, Load reads ~0 (it serves the house directly - see
 *      RaGeneratorForecastTask's fast path), so 100% of the modeled Charge
 *      current is assumed to go into the battery.
 *   3. After the generator stops, until sunrise: historical average night net
 *      Amps again.
 *
 * Deliberately does NOT project a voltage number: Layer 1 of
 * RaGeneratorForecastTask can extrapolate voltage locally because it only ever
 * looks at a short, smooth trailing window, but a generator run is a sharp
 * non-linear excursion (surface voltage spikes to ~57-58V while charging, then
 * relaxes over ~15min after stopping) that a straight-line or simple curve
 * can't honestly represent without real lead-acid discharge-curve data this
 * system doesn't have yet. SoC% (from real Ah integration) is the trustworthy
 * output here.
 *
 * Run:
 *   java -cp "lib/*:target/classes" com.teleonome.cerebellum.rageneratorforecast.RaGeneratorRunSimulator <durationHours> [startDelayMinutes]
 */
public class RaGeneratorRunSimulator {

    // Fitted from the single observed run - see class doc.
    private static final double SPIKE_AMPS            = 120;
    private static final double SPIKE_MINUTES         = 1;
    private static final double PLATEAU_AMPS          = 95;
    private static final double PLATEAU_MINUTES       = 35;
    private static final double TAPER_START_AMPS      = 95;
    private static final double TAPER_RATE_AMPS_PER_MIN = 100.0 / 60.0; // ~100A/hr decline, observed
    private static final double TAPER_FLOOR_AMPS      = 10; // unobserved beyond ~28A; held flat past this as an unknown tail

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: RaGeneratorRunSimulator <durationHours> [startDelayMinutes=0]");
            return;
        }
        double durationHours = Double.parseDouble(args[0]);
        double startDelayMinutes = args.length > 1 ? Double.parseDouble(args[1]) : 0;

        PostgresqlPersistenceManager db = PostgresqlPersistenceManager.instance();
        JSONObject pulse = db.getLastPulse();
        if (pulse == null) {
            System.out.println("No pulse data found - is Ra currently pulsing?");
            return;
        }

        double voltage = getSelfDouble(pulse, "Battery Voltage");
        double soc      = getSelfDouble(pulse, "State of Charge");
        long   nowMillis = pulse.optLong("Pulse Timestamp in Milliseconds", System.currentTimeMillis());

        long generatorStartMillis = nowMillis + (long) (startDelayMinutes * 60_000L);
        long generatorStopMillis  = generatorStartMillis + (long) (durationHours * 3_600_000L);
        long sunriseMillis        = nextSunriseMillis(nowMillis);

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        fmt.setTimeZone(TimeZone.getTimeZone(TIMEZONE));

        if (generatorStopMillis >= sunriseMillis) {
            System.out.println("Note: generator run extends past the next sunrise (" + fmt.format(sunriseMillis)
                    + ") - only the portion before sunrise is simulated.");
        }

        double avgNetAmps = averageHistoricalNightNetAmps(db, nowMillis);

        double preGeneratorHours = Math.max(0, (Math.min(generatorStartMillis, sunriseMillis) - nowMillis) / 3_600_000.0);
        double preGeneratorAh = avgNetAmps * preGeneratorHours;

        double generatorAh = generatorAh(generatorStartMillis, generatorStopMillis, sunriseMillis);

        double postGeneratorHours = Math.max(0,
                (sunriseMillis - Math.min(generatorStopMillis, sunriseMillis)) / 3_600_000.0);
        double postGeneratorAh = avgNetAmps * postGeneratorHours;

        double totalAhDelta = preGeneratorAh + generatorAh + postGeneratorAh;
        double projectedSoc = soc + (totalAhDelta / BATTERY_CAPACITY_AH) * 100.0;
        projectedSoc = Math.max(0, Math.min(100, projectedSoc));

        System.out.println("=== Ra Generator Run Simulation ===");
        System.out.println("Now:                  " + fmt.format(nowMillis)
                + "  V=" + voltage + "V  SoC=" + soc + "%");
        System.out.println("Generator run:         " + fmt.format(generatorStartMillis) + " -> "
                + fmt.format(generatorStopMillis) + "  (" + durationHours + "h)");
        System.out.println("Sunrise target:        " + fmt.format(sunriseMillis));
        System.out.println("Assumed night net load: " + round2(avgNetAmps) + "A (avg over last "
                + NIGHTS_TO_AVERAGE + " nights, sunset->sunrise)");
        System.out.println();
        System.out.println("Pre-generator Ah:      " + round2(preGeneratorAh) + " Ah  (" + round2(preGeneratorHours) + "h @ assumed load)");
        System.out.println("Generator Ah:          +" + round2(generatorAh) + " Ah  (charge-profile model)");
        System.out.println("Post-generator Ah:     " + round2(postGeneratorAh) + " Ah  (" + round2(postGeneratorHours) + "h @ assumed load)");
        System.out.println("Net Ah to sunrise:     " + round2(totalAhDelta) + " Ah");
        System.out.println();
        System.out.println("Projected Sunrise SoC%: " + round2(projectedSoc) + "%");
        System.out.println();
        System.out.println("(No voltage projection - see class doc for why; SoC% is the trustworthy number here.)");
    }

    /** Ah delivered to the battery by the modeled generator profile, clipped to sunrise. */
    private static double generatorAh(long startMillis, long stopMillis, long sunriseMillis) {
        long clippedStop = Math.min(stopMillis, sunriseMillis);
        if (clippedStop <= startMillis) return 0;

        double totalMinutes = (clippedStop - startMillis) / 60_000.0;
        double ampMinutes = 0;
        for (double m = 0; m < totalMinutes; m += 1.0) {
            ampMinutes += generatorAmpsAtMinute(m);
        }
        return ampMinutes / 60.0;
    }

    private static double generatorAmpsAtMinute(double minute) {
        if (minute < SPIKE_MINUTES) return SPIKE_AMPS;
        if (minute < SPIKE_MINUTES + PLATEAU_MINUTES) return PLATEAU_AMPS;
        double taperMinute = minute - (SPIKE_MINUTES + PLATEAU_MINUTES);
        double amps = TAPER_START_AMPS - taperMinute * TAPER_RATE_AMPS_PER_MIN;
        return Math.max(TAPER_FLOOR_AMPS, amps);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double getSelfDouble(JSONObject pulse, String wordName) {
        try {
            String teleonomeName = pulse.getJSONObject("Denome").getString("Name");
            Identity identity = new Identity(teleonomeName, "Purpose", "Sensor Data", "Now", wordName);
            Object value = DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
            if (value == null) return 0.0;
            if (value instanceof Number) return ((Number) value).doubleValue();
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }
}
