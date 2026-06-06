package com.teleonome.cerebellum;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import com.teleonome.framework.TeleonomeConstants;
import com.teleonome.framework.persistence.PostgresqlPersistenceManager;
import com.teleonome.framework.utils.Utils;
import com.teleonome.framework.denome.DenomeUtils;
import com.teleonome.framework.denome.Identity;

import org.apache.commons.io.FileUtils;
import org.apache.commons.jexl2.*;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.management.ManagementFactory;
import java.util.*;

public class Cerebellum {

    private static final String STATUS_FILE_PATH = "/home/pi/Teleonome/CerebellumStatus.json";
    private static final long   PING_INTERVAL_MS = 30_000;

    private MqttClient client;
    private PostgresqlPersistenceManager aDBManager;
    private Logger logger;
    private final int pid;
    private String teleonomeName;
    private long lastPulseMillis;

    private final JexlEngine jexl = new JexlEngine();

    private static final String TIMEZONE             = "Australia/Melbourne";
    private static final long   SUNSET_WINDOW_SECONDS = 1800; // ±30 min

    // Keyed by "className:deviceName" — tasks are long-lived, stateful instances.
    private final Map<String, Task> taskRegistry       = new HashMap<>();
    // Epoch second of the last successful broadcast per task key.
    private final Map<String, Long> lastExecutionEpoch = new HashMap<>();


    		
    public Cerebellum() {
        PropertyConfigurator.configure("/home/pi/Teleonome/lib/Log4J.properties");
        logger = Logger.getLogger(getClass());

        String processName = ManagementFactory.getRuntimeMXBean().getName();
        this.pid = Integer.parseInt(processName.split("@")[0]);
        logger.warn("Cerebellum starting, pid=" + pid);

        aDBManager = PostgresqlPersistenceManager.instance();

        try {
            start();
        } catch (MqttException e) {
            logger.warn(Utils.getStringException(e));
        }
    }

    public static void main(String[] args) {
        new Cerebellum();
    }

    private void start() throws MqttException {
        client = new MqttClient("tcp://localhost:1883", "Cerebellum_Organ", new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        options.setAutomaticReconnect(true);
        options.setKeepAliveInterval(120);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) {
                    logger.warn("Reconnected to Heart broker, re-subscribing topics");
                } else {
                    logger.info("Connected to Heart broker");
                }
                try {
                    client.subscribe(TeleonomeConstants.HEART_TOPIC_STATUS, 1);
                    client.subscribe(TeleonomeConstants.HEART_TOPIC_CEREBELLUM_REQUEST, 1);
                    logger.info("Subscriptions restored");
                } catch (MqttException e) {
                    logger.warn("Failed to re-subscribe after connect: " + Utils.getStringException(e));
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                logger.warn("Heart connection lost: " + cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload());
                logger.debug("Message arrived, topic=" + topic);
                if (topic.equals(TeleonomeConstants.HEART_TOPIC_STATUS)) {
                    absorbPulse(payload);
                } else if (topic.equals(TeleonomeConstants.HEART_TOPIC_CEREBELLUM_REQUEST)) {
                    processRequest(payload);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        client.connect(options);
        logger.info("Cerebellum active.");

        new PingThread().start();
    }

    // ── Pulse handling ────────────────────────────────────────────────────────

    private void absorbPulse(String pulseJson) {
        try {
            JSONObject pulse = new JSONObject(pulseJson);
            JSONObject denome = pulse.getJSONObject("Denome");
            teleonomeName = denome.getString("Name");
            lastPulseMillis = System.currentTimeMillis();
            logger.debug("absorbPulse: teleonomeName=" + teleonomeName);

            // Extract Cerebellum Task Denes from Internal->Cerebellum DeneChain
            List<JSONObject> taskDenes = getCerebellumTaskDenes(pulse);
            if (taskDenes.isEmpty()) {
            	logger.debug("there are no Tasks");
            	return;
            }
            logger.debug("there are " + taskDenes.size() + " Tasks");
            // Sort by Evaluation Position
            taskDenes.sort(Comparator.comparingInt(d ->
                    getDeneWordInt(d, TeleonomeConstants.DENEWORD_CEREBELLUM_EVALUATION_POSITION)));

            Map<String, JSONArray> deviceWords = new LinkedHashMap<>();

            for (JSONObject taskDene : taskDenes) {
                try {
                    // Check Active flag
                    if (!getDeneWordBoolean(taskDene, TeleonomeConstants.DENEWORD_CEREBELLUM_ACTIVE)) {
                        continue;
                    }
                    logger.debug("line 147, taskDene=" + taskDene.toString(4));
                    // Get device name from the typed DeneWord
                   
                    String taskTelepathonType = getDeneWordByType(taskDene,
                            TeleonomeConstants.DENEWORD_TYPE_CEREBELLUM_TELEPATHON_TYPE);
                    logger.debug("line 151, telepathonType=" +taskTelepathonType);
                    // Extract the device's current telepathon from the pulse
                    JSONArray telepathons = extractTelepathons(pulse);
                    JSONObject telepathon;
                    String telepathonType=null;
                    String telepathonName;
                    Identity identity;
                    for(int j=0;j<telepathons.length();j++) {
                    	telepathon = telepathons.getJSONObject(j);
                    	
                    	 telepathonName = telepathon.getString("Name");
                    	 logger.debug("line 156, telepathon=" +telepathonName);
                    	 identity = new Identity(teleonomeName,TeleonomeConstants.NUCLEI_TELEPATHONS, telepathonName,TeleonomeConstants.TELEPATHON_DENE_CONFIGURATION,"Device Type Id"   );;
                    	logger.debug("line 168, identity=" + identity.toString());
                    	 telepathonType = (String) DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                    	 logger.debug("line 169, telepathonType=" +telepathonType); 
                    	if (telepathonType == null) continue;
                    	
                         if(!taskTelepathonType.equals(telepathonType)) {
                        	 logger.debug("line 173,skiping task for  telepathon=" +telepathonName);
                        	 continue;
                         }
                         
                         logger.debug("about to evaluate ");
                         // Evaluate JEXL Expression with condition pointer bindings
                         String expression = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_EXPRESSION);
                         if (!evaluateExpression(expression, taskDene, pulse)) {
                             logger.debug("Expression '" + expression + "' false for " + telepathonType);
                             continue;
                         }

                         // Get or create the task instance
                         String className = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_TASK_TRUE_EXPRESSION);
                         Task task = getOrCreateTask(className, telepathonType);
                         logger.debug("line 173 className= " +className + " task is null=" + (task == null));

                         if (task == null) continue;

                         // Execute — task returns empty array if not yet time to publish results
                         long startTime = System.currentTimeMillis();
                         JSONArray words = task.process(telepathon);
                         long endTime = System.currentTimeMillis();

                         if (words.length() == 0) continue;

                         // Gate 1+2: match execution time slot AND check it hasn't run today
                         String executionTime = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME);
                         String frequency = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_FREQUENCY);
                         String matchedSlot = matchExecutionSlot(executionTime, frequency, telepathon);
                         logger.debug("line 189 executionTime= " +executionTime + " frequency=" + frequency + " matchedSlot=" + matchedSlot);
                         if (matchedSlot == null) {
                             logger.debug("No execution slot matched for "
                                     + task.getName() + "/" + telepathonType);
                             continue;
                         }
                         String trackingKey = className + ":" + telepathonType + ":" + matchedSlot;
                         logger.debug("line 196 trackingKey= " +trackingKey);
                         
                         if (!isFrequencyAllowed(trackingKey)) {
                             logger.debug("Slot '" + matchedSlot + "' already ran today for "
                                     + task.getName() + "/" + telepathonType);
                             continue;
                         }

                         // Record this broadcast
                         lastExecutionEpoch.put(trackingKey, endTime / 1000);

                         // Append mandatory timing DeneWords
                         words.put(timingDeneWord(task.getName() + " Execution Time",
                                 String.valueOf(endTime), "Long"));
                         words.put(timingDeneWord(task.getName() + " Duration",
                                 String.valueOf(endTime - startTime), "Integer"));

                         // Accumulate into device bucket
                         JSONArray bucket = deviceWords.computeIfAbsent(telepathonType,
                                 k -> new JSONArray());
                         for (int i = 0; i < words.length(); i++) bucket.put(words.get(i));

                         // If this task Dene declares an Annabelle action to activate,
                         // carry the pointer through to the cerebellumStatus so that
                         // updateCerebellumPurposeDene() can activate it generically.
                         String actionPointer = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_ANNABELLE_ACTION_POINTER);
                         if (actionPointer != null && !actionPointer.isEmpty()) {
                             bucket.put(timingDeneWord(
                                     TeleonomeConstants.DENEWORD_CEREBELLUM_ANNABELLE_ACTION_POINTER,
                                     actionPointer, "String"));
                         }

                         logger.info("Task " + task.getName() + " for " + telepathonType
                                 + " produced " + words.length() + " DeneWords in "
                                 + (endTime - startTime) + "ms");
                    }
                   
                    

                } catch (Exception e) {
                    logger.warn("Task evaluation error: " + Utils.getStringException(e));
                }
            }

            if (!deviceWords.isEmpty()) {
                JSONObject deneChain = buildCerebellumDeneChain(deviceWords);
                broadcastAnalysis(deneChain);
            }

        } catch (Exception e) {
            logger.warn("absorbPulse error: " + Utils.getStringException(e));
        }
    }

    // ── Pulse navigation helpers ──────────────────────────────────────────────

    private List<JSONObject> getCerebellumTaskDenes(JSONObject pulse) {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONArray nuclei = pulse.getJSONObject("Denome").getJSONArray("Nuclei");
            for (int i = 0; i < nuclei.length(); i++) {
                JSONObject nucleus = nuclei.getJSONObject(i);
                if (!TeleonomeConstants.NUCLEI_INTERNAL.equals(nucleus.getString("Name"))) continue;
                JSONArray chains = nucleus.getJSONArray("DeneChains");
                for (int j = 0; j < chains.length(); j++) {
                    JSONObject chain = chains.getJSONObject(j);
                    if (!TeleonomeConstants.DENECHAIN_INTERNAL_CEREBELLUM.equals(chain.getString("Name"))) continue;
                    JSONArray denes = chain.getJSONArray("Denes");
                    for (int k = 0; k < denes.length(); k++) {
                        JSONObject dene = denes.getJSONObject(k);
                        if (TeleonomeConstants.DENE_TYPE_CEREBELLUM_TASK.equals(dene.optString("DeneType"))) {
                            result.add(dene);
                        }
                    }
                    return result;
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private JSONArray extractTelepathons(JSONObject pulse) {
        try {
            JSONArray nuclei = pulse.getJSONObject("Denome").getJSONArray("Nuclei");
            for (int i = 0; i < nuclei.length(); i++) {
                JSONObject nucleus = nuclei.getJSONObject(i);
                if (!TeleonomeConstants.NUCLEI_TELEPATHONS.equals(nucleus.getString("Name"))) continue;
                return nucleus.getJSONArray("DeneChains");
            }
        } catch (Exception ignored) {}
        return null;
    }
    
    private JSONObject extractTelepathon(JSONObject pulse, String deviceName) {
        try {
            JSONArray nuclei = pulse.getJSONObject("Denome").getJSONArray("Nuclei");
            for (int i = 0; i < nuclei.length(); i++) {
                JSONObject nucleus = nuclei.getJSONObject(i);
                if (!TeleonomeConstants.NUCLEI_TELEPATHONS.equals(nucleus.getString("Name"))) continue;
                JSONArray chains = nucleus.getJSONArray("DeneChains");
                for (int j = 0; j < chains.length(); j++) {
                    JSONObject chain = chains.getJSONObject(j);
                    if (deviceName.equals(chain.getString("Name"))) return chain;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Expression evaluation ─────────────────────────────────────────────────

    private boolean evaluateExpression(String expression, JSONObject taskDene, JSONObject pulse) {
        try {
            JexlContext context = new MapContext();
            JSONArray words = taskDene.getJSONArray("DeneWords");
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                if (TeleonomeConstants.DENEWORD_TYPE_CEREBELLUM_TASK_CONDITION_POINTER.equals(
                        word.optString(TeleonomeConstants.DENEWORD_DENEWORD_TYPE_ATTRIBUTE))) {
                    String varName  = word.getString(TeleonomeConstants.DENEWORD_NAME_ATTRIBUTE);
                    String pointer  = word.getString(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                    Object value    = resolvePointer(pulse, pointer);
                    if (value != null) context.set(varName, value);
                }
            }
            Expression expr = jexl.createExpression(expression);
            Object result = expr.evaluate(context);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            logger.warn("Expression evaluation error for '" + expression + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolves a pointer of the form "@NucleusName:...:TargetName" against the pulse JSON.
     * Searches for a DeneWord named TargetName within the specified nucleus.
     */
    private Object resolvePointer(JSONObject pulse, String pointer) {
        try {
            if (pointer.startsWith("@")) pointer = pointer.substring(1);
            String[] parts    = pointer.split(":");
            String nucleusName = parts[0];
            String targetName  = parts[parts.length - 1];

            JSONArray nuclei = pulse.getJSONObject("Denome").getJSONArray("Nuclei");
            for (int i = 0; i < nuclei.length(); i++) {
                JSONObject nucleus = nuclei.getJSONObject(i);
                if (!nucleusName.equals(nucleus.getString("Name"))) continue;
                JSONArray chains = nucleus.getJSONArray("DeneChains");
                for (int j = 0; j < chains.length(); j++) {
                    Object val = searchDeneWordValue(chains.getJSONObject(j), targetName);
                    if (val != null) return val;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object searchDeneWordValue(JSONObject deneChain, String wordName) {
        try {
            JSONArray denes = deneChain.getJSONArray("Denes");
            for (int i = 0; i < denes.length(); i++) {
                JSONArray words = denes.getJSONObject(i).optJSONArray("DeneWords");
                if (words == null) continue;
                for (int j = 0; j < words.length(); j++) {
                    JSONObject word = words.getJSONObject(j);
                    if (wordName.equals(word.optString(TeleonomeConstants.DENEWORD_NAME_ATTRIBUTE))) {
                        return word.get(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Execution slot matching ───────────────────────────────────────────────

    /**
     * Returns the identifier of the matched execution slot, or null if no slot
     * matches the current wall-clock time.
     *
     * For frequency "Daily": Execution Time is a single value ("Sunset",
     * "Sunrise"). Returns the value if it matches now, e.g. "Sunset".
     *
     * For frequency "Hours In A Day": Execution Time is a JSON-array string,
     * e.g. "[12,15,\"Sunset\"]". Returns the first matching element as a string
     * ("12", "15", "Sunset"), so each slot gets its own independent daily gate.
     */
    private String matchExecutionSlot(String executionTime, String frequency, JSONObject telepathon) {
        if (TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_FREQUENCY_HOURS_IN_A_DAY.equals(frequency)) {
            try {
                JSONArray slots = new JSONArray(executionTime);
                for (int i = 0; i < slots.length(); i++) {
                    Object slot = slots.get(i);
                    if (slot instanceof Number) {
                        int targetHour = ((Number) slot).intValue();
                        if (isCurrentHour(targetHour)) return String.valueOf(targetHour);
                    } else if (TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME_SUNSET
                            .equals(slot) && isNearSunset(telepathon)) {
                        return TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME_SUNSET;
                    } else if (TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME_SUNRISE
                            .equals(slot) && isNearSunrise(telepathon)) {
                        return TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME_SUNRISE;
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse Execution Time array '" + executionTime
                        + "': " + e.getMessage());
            }
            return null;
        }

        // Default: single execution time value
        if (TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME_SUNSET.equals(executionTime)
                && isNearSunset(telepathon)) {
            return TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME_SUNSET;
        }
        if (TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME_SUNRISE.equals(executionTime)
                && isNearSunrise(telepathon)) {
            return TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME_SUNRISE;
        }
        return null;
    }

    private boolean isCurrentHour(int targetHour) {
        return Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE))
                .get(Calendar.HOUR_OF_DAY) == targetHour;
    }

    private boolean isNearSunset(JSONObject telepathon) {
        try {
            double lat = getTelepathonDouble(telepathon, "Configuration", "Latitude");
            double lon = getTelepathonDouble(telepathon, "Configuration", "longitude");
            if (lat == 0) return false;
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
            long sunsetEpoch = new SunriseSunsetCalculator(
                    new Location(String.valueOf(lat), String.valueOf(lon)), TIMEZONE)
                    .getOfficialSunsetCalendarForDate(now).getTimeInMillis() / 1000;
            return Math.abs(System.currentTimeMillis() / 1000 - sunsetEpoch) <= SUNSET_WINDOW_SECONDS;
        } catch (Exception e) {
            logger.warn("isNearSunset error: " + e.getMessage());
            return false;
        }
    }

    private boolean isNearSunrise(JSONObject telepathon) {
        try {
            double lat = getTelepathonDouble(telepathon, "Configuration", "Latitude");
            double lon = getTelepathonDouble(telepathon, "Configuration", "longitude");
            if (lat == 0) return false;
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
            long sunriseEpoch = new SunriseSunsetCalculator(
                    new Location(String.valueOf(lat), String.valueOf(lon)), TIMEZONE)
                    .getOfficialSunriseCalendarForDate(now).getTimeInMillis() / 1000;
            return Math.abs(System.currentTimeMillis() / 1000 - sunriseEpoch) <= SUNSET_WINDOW_SECONDS;
        } catch (Exception e) {
            logger.warn("isNearSunrise error: " + e.getMessage());
            return false;
        }
    }

    /** Returns true if this tracking key has not already fired today. */
    private boolean isFrequencyAllowed(String trackingKey) {
        Long lastEpoch = lastExecutionEpoch.get(trackingKey);
        if (lastEpoch == null) return true;
        Calendar last = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        last.setTimeInMillis(lastEpoch * 1000L);
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        return last.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR)
                || last.get(Calendar.YEAR)        != now.get(Calendar.YEAR);
    }

    private double getTelepathonDouble(JSONObject telepathon, String deneName, String wordName) {
        try {
            JSONArray denes = telepathon.getJSONArray("Denes");
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

    // ── Task registry ─────────────────────────────────────────────────────────

    private Task getOrCreateTask(String className, String deviceName) {
        String key = className + ":" + deviceName;
        return taskRegistry.computeIfAbsent(key, k -> {
            try {
                Task t = (Task) Class.forName(className)
                        .getConstructor(String.class)
                        .newInstance(deviceName);
                logger.info("Instantiated task: " + className + " for device: " + deviceName);
                return t;
            } catch (Exception e) {
                logger.warn("Failed to instantiate task " + className + ": " + e.getMessage());
                return null;
            }
        });
    }

    // ── DeneChain builder & broadcast ─────────────────────────────────────────

    private JSONObject buildCerebellumDeneChain(Map<String, JSONArray> deviceWords) {
        JSONObject deneChain = new JSONObject();
        deneChain.put("Name", TeleonomeConstants.DENECHAIN_PURPOSE_CEREBELLUM);
        deneChain.put("Seconds Time", System.currentTimeMillis() / 1000);
        JSONArray denes = new JSONArray();
        for (Map.Entry<String, JSONArray> entry : deviceWords.entrySet()) {
            JSONObject deviceDene = new JSONObject();
            deviceDene.put("Name", entry.getKey());
            deviceDene.put("DeneWords", entry.getValue());
            denes.put(deviceDene);
        }
        deneChain.put("Denes", denes);
        return deneChain;
    }

    private void broadcastAnalysis(JSONObject deneChain) {
        try {
            MqttMessage message = new MqttMessage(deneChain.toString().getBytes());
            message.setQos(TeleonomeConstants.HEART_QUALITY_OF_SERVICE);
            message.setRetained(false);
            client.publish(TeleonomeConstants.HEART_TOPIC_CEREBELLUM_STATUS, message);
            logger.info("Cerebellum analysis published to "
                    + TeleonomeConstants.HEART_TOPIC_CEREBELLUM_STATUS);
        } catch (Exception e) {
            logger.warn("broadcastAnalysis failed: " + Utils.getStringException(e));
        }
    }

    private void processRequest(String requestJson) {
        // Handle incoming Cerebellum_Request messages.
    }

    // ── DeneWord accessors (navigate a task Dene) ─────────────────────────────

    private boolean getDeneWordBoolean(JSONObject dene, String wordName) {
        try {
            JSONArray words = dene.getJSONArray("DeneWords");
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                if (wordName.equals(word.optString(TeleonomeConstants.DENEWORD_NAME_ATTRIBUTE))) {
                    return word.getBoolean(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private int getDeneWordInt(JSONObject dene, String wordName) {
        try {
            JSONArray words = dene.getJSONArray("DeneWords");
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                if (wordName.equals(word.optString(TeleonomeConstants.DENEWORD_NAME_ATTRIBUTE))) {
                    return word.getInt(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                }
            }
        } catch (Exception ignored) {}
        return Integer.MAX_VALUE;
    }

    private String getDeneWordString(JSONObject dene, String wordName) {
        try {
            JSONArray words = dene.getJSONArray("DeneWords");
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                if (wordName.equals(word.optString(TeleonomeConstants.DENEWORD_NAME_ATTRIBUTE))) {
                    return word.getString(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getDeneWordByType(JSONObject dene, String deneWordType) {
        try {
            JSONArray words = dene.getJSONArray("DeneWords");
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                if (deneWordType.equals(word.optString(TeleonomeConstants.DENEWORD_DENEWORD_TYPE_ATTRIBUTE))) {
                    return word.getString(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private JSONObject timingDeneWord(String name, String value, String type) {
        JSONObject w = new JSONObject();
        w.put("Name",  name);
        w.put("Value", value);
        w.put("Type",  type);
        return w;
    }

    // ── Status file ───────────────────────────────────────────────────────────

    private void writePing() {
        try {
            JSONObject status = new JSONObject();
            status.put("cerebellumPid", pid);
            status.put(TeleonomeConstants.DATATYPE_TIMESTAMP_MILLISECONDS, System.currentTimeMillis());
            FileUtils.writeStringToFile(new File(STATUS_FILE_PATH), status.toString(4), "UTF-8");
        } catch (Exception e) {
            logger.warn("Failed to write CerebellumStatus.json: " + e.getMessage());
        }
    }

    // ── Ping thread ───────────────────────────────────────────────────────────

    private class PingThread extends Thread {
        PingThread() { setDaemon(true); setName("CerebellumPing"); }

        @Override
        public void run() {
            while (true) {
                writePing();
                try { Thread.sleep(PING_INTERVAL_MS); } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }
}
