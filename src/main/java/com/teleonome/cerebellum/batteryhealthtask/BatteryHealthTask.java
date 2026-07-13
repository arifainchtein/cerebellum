package com.teleonome.cerebellum.batteryhealthtask;

import com.teleonome.cerebellum.Task;
import com.teleonome.cerebellum.health.CheckResult;
import com.teleonome.cerebellum.health.Status;
import com.teleonome.cerebellum.health.StatusEvaluator;
import com.teleonome.cerebellum.health.StatusEvaluator.Rule;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * Classifies a telepathon's own reported Battery Voltage (1S LiFePO4 —
 * Daffodil, Langley, Gloria) into OK/WARNING/CRITICAL every pulse.
 *
 * Thresholds match the voltage table already used by PulseTask for SOC
 * estimation: below 3.10V (~30% SOC) is WARNING, below 2.90V (~5% SOC,
 * near the 2.50V empty floor) is CRITICAL.
 *
 * This only covers a telepathon's own battery — it does not apply to a
 * Teleonome's own local sensor readings (e.g. Ra's house battery bank),
 * which aren't telepathon data and need a different hook into Cerebellum.
 */
public class BatteryHealthTask implements Task {

    private static final double CRITICAL_VOLTAGE = 2.90;
    private static final double WARNING_VOLTAGE  = 3.10;

    private final String deviceName;
    private final Logger logger;
    private final StatusEvaluator<Double> evaluator;

    public BatteryHealthTask(String teleonomeName, String deviceName) {
        this.deviceName = deviceName;
        this.logger = Logger.getLogger(getClass());
        this.evaluator = new StatusEvaluator<>("Battery Voltage", Arrays.asList(
                new Rule<>(v -> v <= 0,
                        Status.WARNING, "no reading yet"),
                new Rule<>(v -> v <= CRITICAL_VOLTAGE,
                        Status.CRITICAL, "at or below " + CRITICAL_VOLTAGE + "V"),
                new Rule<>(v -> v <= WARNING_VOLTAGE,
                        Status.WARNING, "at or below " + WARNING_VOLTAGE + "V")
        ));
    }

    @Override public String getName()       { return "BatteryHealthTask"; }
    @Override public String getDeviceName() { return deviceName; }

    @Override
    public JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception {
        double voltage = getDeneWordDouble(telepathon, "Purpose", "Battery Voltage");

        CheckResult result = evaluator.evaluate(voltage);
        logger.info("BatteryHealthTask[" + deviceName + "]: " + result);

        JSONArray words = new JSONArray();
        words.put(deneWord("Battery Health Status", result.getStatus().name(), "String"));
        words.put(deneWord("Battery Health Message", result.getMessage(), "String"));
        return words;
    }

    private double getDeneWordDouble(JSONObject deneChain, String deneName, String wordName) {
        try {
            JSONArray denes = deneChain.getJSONArray("Denes");
            for (int i = 0; i < denes.length(); i++) {
                JSONObject dene = denes.getJSONObject(i);
                if (deneName.equals(dene.getString("Name"))) {
                    JSONArray words = dene.getJSONArray("DeneWords");
                    for (int j = 0; j < words.length(); j++) {
                        JSONObject word = words.getJSONObject(j);
                        if (wordName.equals(word.getString("Name"))) {
                            return word.getDouble("Value");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    private JSONObject deneWord(String name, Object value, String valueType) {
        JSONObject w = new JSONObject();
        w.put("Name", name);
        w.put("Value", value);
        w.put("Value Type", valueType);
        w.put("Required", true);
        return w;
    }
}
