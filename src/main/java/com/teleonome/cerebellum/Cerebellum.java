package com.teleonome.cerebellum;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import com.teleonome.framework.TeleonomeConstants;
import com.teleonome.framework.persistence.PostgresqlPersistenceManager;
import com.teleonome.framework.utils.Utils;
import com.teleonome.framework.denome.DenomeUtils;
import com.teleonome.framework.denome.Identity;
import com.teleonome.cerebellum.telepathonregistry.TelepathonRegistryTask;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Cerebellum {

    private static final String STATUS_FILE_PATH = "/home/pi/Teleonome/CerebellumStatus.json";
    private static final long   PING_INTERVAL_MS = 30_000;
    // Time-based counterpart to the pulse-driven (event-based) Task dispatch below -
    // same split the denome's own Mutation system makes between time-based and
    // event-based mutations. Runs independently of whether any pulse arrives, so it
    // can notice a device that has stopped pulsing entirely, which a pulse-driven
    // Task structurally never can.
    private static final long   REGISTRY_SWEEP_INTERVAL_MS = 5 * 60_000;

    private MqttClient client;
    // Separate connection dedicated to Hippocampus request/response. Kept apart
    // from `client` because Status-message handling (absorbPulse -> task.process)
    // runs synchronously on Paho's single callback thread for `client`; if a task
    // blocked on a Hippocampus reply over that same connection, the reply could
    // never be delivered (it needs that same thread to be free to receive it).
    private MqttClient hippocampusClient;
    private final ConcurrentHashMap<String, CompletableFuture<JSONObject>> pendingHippocampusRequests = new ConcurrentHashMap<>();
    private static final int HIPPOCAMPUS_QUERY_TIMEOUT_SECONDS = 10;
    private PostgresqlPersistenceManager aDBManager;
    private Logger logger;
    private final int pid;
    private String teleonomeName;
    private long lastPulseMillis;

    private final JexlEngine jexl = new JexlEngine();

    private static final String TIMEZONE             = "Australia/Melbourne";
    private static final long   SUNSET_WINDOW_SECONDS = 1800; // ±30 min

    // Marker value for a Cerebellum Task Dene's "Telepathon Type" DeneWord that means
    // "this task reads the pulsing teleonome's own denome" (see Task.processSelf) rather
    // than being matched against an incoming Telepathon. Not a DeneWord Type of its own -
    // reuses the existing typed DeneWord so wiring a Self task looks identical to wiring
    // a Telepathon-scoped one, just with this value.
    private static final String TELEPATHON_TYPE_SELF = "Self";

    // Keyed by "className:deviceName" — tasks are long-lived, stateful instances.
    private final Map<String, Task>    taskRegistry       = new HashMap<>();
    // Epoch second of the last successful broadcast per task key.
    private final Map<String, Long>    lastExecutionEpoch = new HashMap<>();
    // Latest words per task: key = "className|telepathonType", persists across pulses.
    private final Map<String, JSONArray> latestTaskWords  = new LinkedHashMap<>();
    // Epoch of the last scheduled-slot (non-Every-Pulse) broadcast; used as Seconds Time
    // so that unslotted LiveSoc broadcasts don't trigger new Mnemosyne writes.
    private long lastAnalysisEpoch = 0;


    		
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

        startHippocampusClient();

        new PingThread().start();
        new RegistrySweepThread().start();
    }

    private void startHippocampusClient() throws MqttException {
        hippocampusClient = new MqttClient("tcp://localhost:1883", "Cerebellum_Hippocampus_Query", new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setKeepAliveInterval(120);

        hippocampusClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                try {
                    hippocampusClient.subscribe(TeleonomeConstants.HEART_TOPIC_HIPPOCAMPUS_RESPONSE + "/#", 1);
                    logger.info("Hippocampus query connection " + (reconnect ? "reconnected" : "connected")
                            + ", subscribed to response channel");
                } catch (MqttException e) {
                    logger.warn("Failed to subscribe to Hippocampus response channel: " + Utils.getStringException(e));
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                logger.warn("Hippocampus query connection lost: " + cause.getMessage());
                pendingHippocampusRequests.forEach((id, f) ->
                        f.completeExceptionally(new RuntimeException("Hippocampus MQTT connection lost")));
                pendingHippocampusRequests.clear();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                try {
                    JSONObject response = new JSONObject(new String(message.getPayload()));
                    String requestId = response.optString("RequestId", "");
                    CompletableFuture<JSONObject> f = pendingHippocampusRequests.remove(requestId);
                    if (f != null) f.complete(response);
                } catch (Exception e) {
                    logger.warn("Hippocampus response parse error: " + e.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        hippocampusClient.connect(options);
    }

    /**
     * Queries Hippocampus's in-memory short-term history for a DeneWord.
     * Blocks the calling (task-processing) thread until a response arrives or
     * HIPPOCAMPUS_QUERY_TIMEOUT_SECONDS elapses. Safe to call from inside a
     * Task's process() — runs over the dedicated hippocampusClient connection,
     * not the Status-handling one.
     *
     * @param identity full identity string, e.g.
     *                  "@ChinampaMonitor:Telepathons:Chinampa:Purpose:Pump Relay Status"
     * @param rangeMs   how much history to ask for, in milliseconds back from now
     * @return the "Data" JSONArray from Hippocampus's response, or an empty
     *         array if Hippocampus has nothing for that identity or times out
     */
    JSONArray queryHippocampus(String identity, long rangeMs) {
        if (hippocampusClient == null || !hippocampusClient.isConnected()) {
            logger.debug("queryHippocampus: not connected, skipping for " + identity);
            return new JSONArray();
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pendingHippocampusRequests.put(requestId, future);
        try {
            JSONObject payload = new JSONObject();
            payload.put("Identity", identity);
            payload.put("Range", rangeMs);
            payload.put("RequestId", requestId);

            MqttMessage msg = new MqttMessage(payload.toString().getBytes());
            msg.setQos(1);
            hippocampusClient.publish("Hippocampus_Request", msg);

            JSONObject response = future.get(HIPPOCAMPUS_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return response.optJSONArray("Data") != null ? response.getJSONArray("Data") : new JSONArray();
        } catch (TimeoutException e) {
            logger.debug("queryHippocampus: timed out for " + identity);
            return new JSONArray();
        } catch (Exception e) {
            logger.warn("queryHippocampus error for " + identity + ": " + e.getMessage());
            return new JSONArray();
        } finally {
            pendingHippocampusRequests.remove(requestId);
        }
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

            boolean slottedTaskFired   = false;
            boolean anyTaskFiredThisPulse = false;

            for (JSONObject taskDene : taskDenes) {
                try {
                    // Check Active flag
                    if (!getDeneWordBoolean(taskDene, TeleonomeConstants.DENEWORD_CEREBELLUM_ACTIVE)) {
                        continue;
                    }
                    logger.debug("line 147, taskDene=" + taskDene.getString(TeleonomeConstants.DENE_NAME_ATTRIBUTE));
                    // Get device name from the typed DeneWord

                    String taskTelepathonType = getDeneWordByType(taskDene,
                            TeleonomeConstants.DENEWORD_TYPE_CEREBELLUM_TELEPATHON_TYPE);
                    logger.debug("line 151, telepathonType=" +taskTelepathonType);

                    // "Self" tasks read the pulsing teleonome's own denome (e.g. Ra's
                    // house battery readings, or data pulled in from another teleonome
                    // into Purpose:External Data) rather than an incoming Telepathon -
                    // there's no Telepathon to match against, so skip that loop entirely
                    // and hand the task the whole pulse via processSelf().
                    if (TELEPATHON_TYPE_SELF.equals(taskTelepathonType)) {
                        String expression = getDeneWordString(taskDene,
                                TeleonomeConstants.DENEWORD_CEREBELLUM_EXPRESSION);
                        if (expression != null && !expression.isEmpty()
                                && !evaluateSelfExpression(expression, taskDene, pulse)) {
                            logger.debug("Self expression '" + expression + "' false");
                            continue;
                        }

                        String className = getDeneWordString(taskDene,
                                TeleonomeConstants.DENEWORD_CEREBELLUM_TASK_TRUE_EXPRESSION);
                        Task task = getOrCreateTask(className, teleonomeName);
                        if (task == null) continue;

                        String executionTime = getDeneWordString(taskDene,
                                TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME);
                        String frequency = getDeneWordString(taskDene,
                                TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_FREQUENCY);
                        // matchExecutionSlot only dereferences its telepathon argument for
                        // Sunset/Sunrise slots (Configuration:Latitude/longitude); null is
                        // safe here since Self tasks don't have a telepathon to read that from.
                        String matchedSlot = matchExecutionSlot(executionTime, frequency, null);
                        if (matchedSlot == null) {
                            logger.debug("No execution slot matched for " + task.getName() + "/Self");
                            continue;
                        }
                        String trackingKey = className + ":" + teleonomeName + ":" + matchedSlot;
                        if (!isFrequencyAllowed(trackingKey)) {
                            logger.debug("Slot '" + matchedSlot + "' already ran today for "
                                    + task.getName() + "/Self");
                            continue;
                        }
                        lastExecutionEpoch.put(trackingKey, System.currentTimeMillis() / 1000);

                        long startTime = System.currentTimeMillis();
                        JSONArray words = task.processSelf(pulse, matchedSlot);
                        long endTime = System.currentTimeMillis();
                        if (words.length() == 0) continue;

                        words.put(timingDeneWord(task.getName() + " Execution Time",
                                String.valueOf(endTime), "Long"));
                        words.put(timingDeneWord(task.getName() + " Duration",
                                String.valueOf(endTime - startTime), "Integer"));

                        String actionPointer = getDeneWordString(taskDene,
                                TeleonomeConstants.DENEWORD_CEREBELLUM_ANNABELLE_ACTION_POINTER);
                        if (actionPointer != null && !actionPointer.isEmpty()) {
                            words.put(timingDeneWord(
                                    TeleonomeConstants.DENEWORD_CEREBELLUM_ANNABELLE_ACTION_POINTER,
                                    actionPointer, "String"));
                        }

                        latestTaskWords.put(teleonomeName + "|" + className, words);
                        anyTaskFiredThisPulse = true;
                        if (!TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_FREQUENCY_EVERY_PULSE
                                .equals(frequency)) {
                            slottedTaskFired = true;
                        }

                        logger.info("Task " + task.getName() + " for " + teleonomeName
                                + " (Self) produced " + words.length() + " DeneWords in "
                                + (endTime - startTime) + "ms");
                        logger.debug(words.toString(4));
                        continue;
                    }

                    // Extract the device's current telepathon from the pulse
                    JSONArray telepathons = extractTelepathons(pulse);
                    JSONObject telepathon;
                    String telepathonType=null;
                    String telepathonName;
                    Identity identity;
                    for(int j=0;j<telepathons.length();j++) {
                    	telepathon = telepathons.getJSONObject(j);
                    	
                    	 telepathonName = telepathon.getString("Name");
                    	// logger.debug("line 156, telepathon=" +telepathonName);
                    	 identity = new Identity(teleonomeName,TeleonomeConstants.NUCLEI_TELEPATHONS, telepathonName,TeleonomeConstants.TELEPATHON_DENE_CONFIGURATION,"Device Type Id"   );;
                   // 	logger.debug("line 168, identity=" + identity.toString());
                    	 telepathonType = (String) DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                   // 	 logger.debug("line 169, telepathonType=" +telepathonType); 
                    	if (telepathonType == null) continue;
                    	
                         if(!taskTelepathonType.equals(telepathonType)) {
                       // 	 logger.debug("line 173,skiping task for  telepathon=" +telepathonName);
                        	 continue;
                         }
                         
                         logger.debug("about to evaluate ");
                         // Evaluate JEXL Expression with condition pointer bindings
                         String expression = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_EXPRESSION);
                         if (!evaluateExpression(telepathonName,expression, taskDene, pulse)) {
                             logger.debug("Expression '" + expression + "' false for " + telepathonType);
                             continue;
                         }

                         // Get or create the task instance
                         String className = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_TASK_TRUE_EXPRESSION);
                         Task task = getOrCreateTask(className, telepathonName);
                         logger.debug("line 191 className= " +className + " task is null=" + (task == null));

                         if (task == null) continue;

                         // Match execution slot before calling the task so it receives the slot name
                         String executionTime = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_TIME);
                         String frequency = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_FREQUENCY);
                         String matchedSlot = matchExecutionSlot(executionTime, frequency, telepathon);
                         logger.debug("executionTime=" + executionTime + " frequency=" + frequency + " matchedSlot=" + matchedSlot);
                         if (matchedSlot == null) {
                             logger.debug("No execution slot matched for " + task.getName() + "/" + telepathonType);
                             continue;
                         }
                         String trackingKey = className + ":" + telepathonName + ":" + matchedSlot;
                         if (!isFrequencyAllowed(trackingKey)) {
                             logger.debug("Slot '" + matchedSlot + "' already ran today for "
                                     + task.getName() + "/" + telepathonType);
                             continue;
                         }

                         // Claim the slot now so re-entrant pulses within the same hour
                         // don't re-fire even if the task returns empty this time.
                         lastExecutionEpoch.put(trackingKey, System.currentTimeMillis() / 1000);

                         // Execute — task returns empty array if it has nothing ready yet
                         long startTime = System.currentTimeMillis();
                         JSONArray words = task.process(telepathon, matchedSlot);
                         long endTime = System.currentTimeMillis();
                         logger.debug("words=" + words);
                         if (words.length() == 0) continue;

                         // Append mandatory timing DeneWords
                         words.put(timingDeneWord(task.getName() + " Execution Time",
                                 String.valueOf(endTime), "Long"));
                         words.put(timingDeneWord(task.getName() + " Duration",
                                 String.valueOf(endTime - startTime), "Integer"));

                         // If this task Dene declares an Annabelle action to activate,
                         // carry the pointer through to the cerebellumStatus so that
                         // updateCerebellumPurposeDene() can activate it generically.
                         String actionPointer = getDeneWordString(taskDene,
                                 TeleonomeConstants.DENEWORD_CEREBELLUM_ANNABELLE_ACTION_POINTER);
                         if (actionPointer != null && !actionPointer.isEmpty()) {
                             words.put(timingDeneWord(
                                     TeleonomeConstants.DENEWORD_CEREBELLUM_ANNABELLE_ACTION_POINTER,
                                     actionPointer, "String"));
                         }

                         // Store in persistent per-task cache, keyed by "telepathonType|className".
                         // The broadcast always merges all cached task words so that a LiveSoc
                         // pulse doesn't lose the last SolarAnalysis detail words.
                         latestTaskWords.put(telepathonName + "|" + className, words);
                         anyTaskFiredThisPulse = true;
                         if (!TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_FREQUENCY_EVERY_PULSE
                                 .equals(frequency)) {
                             slottedTaskFired = true;
                         }

                         logger.info("Task " + task.getName() + " for " + telepathonName
                                 + " produced " + words.length() + " DeneWords in "
                                 + (endTime - startTime) + "ms");
                         logger.debug(words.toString(4));
                    }
                   
                    

                } catch (Exception e) {
                    logger.warn("Task evaluation error: " + Utils.getStringException(e));
                }
            }

            if (anyTaskFiredThisPulse) {
                if (slottedTaskFired) {
                    lastAnalysisEpoch = System.currentTimeMillis() / 1000;
                }
                // Use lastAnalysisEpoch as Seconds Time so unslotted LiveSoc pulses don't
                // trigger new Mnemosyne writes in DenomeManager (epoch guard stays closed).
                long broadcastEpoch = lastAnalysisEpoch > 0
                        ? lastAnalysisEpoch : System.currentTimeMillis() / 1000;
                broadcastAnalysis(buildCerebellumDeneChain(mergeLatestTaskWords(), broadcastEpoch));
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

    private Object coerceJexlValue(Object value) {
        if (!(value instanceof String)) return value;
        String s = (String) value;
        if ("true".equalsIgnoreCase(s))  return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return s;
    }


    private boolean evaluateExpression(String telepathonName, String expression, JSONObject taskDene, JSONObject pulse) {
    	logger.debug("line 328,expression=" + expression + " taskDene=" + taskDene.toString() );
    	Identity identity;
        try {
            JexlContext context = new MapContext();
            JSONArray words = taskDene.getJSONArray("DeneWords");
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                if (TeleonomeConstants.DENEWORD_TYPE_CEREBELLUM_TASK_CONDITION_POINTER.equals(
                        word.optString(TeleonomeConstants.DENEWORD_DENEWORD_TYPE_ATTRIBUTE))) {
                    String varName  = word.getString(TeleonomeConstants.DENEWORD_NAME_ATTRIBUTE);
                    //
                    // the value is just @Purpose:Using Solar Power" 
                    String[] tokens  = word.getString(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE).replace("@","").split(":");
               	 identity = new Identity(teleonomeName,TeleonomeConstants.NUCLEI_TELEPATHONS, telepathonName,tokens[0],tokens[1]   );;
                 
                    Object value = DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                	
                    logger.debug("line 345,varName=" + varName + " value=" + value );
                    if (value != null) context.set(varName, coerceJexlValue(value));
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
     * Condition evaluator for Self tasks. Telepathon-scoped evaluateExpression()
     * resolves a "Task Condition Pointer" as "@DeneName:DeneWordName" relative to
     * one Telepathon's own Denes - there's no such fixed shape for a Self task, whose
     * data can live anywhere in the teleonome's own tree (Purpose:Sensor Data,
     * Purpose:External Data pulled in from another teleonome, Internal:Components,
     * etc). So here the pointer is the full absolute path instead:
     * "@Nucleus:DeneChain:Dene:DeneWord", e.g. "@Purpose:Sensor Data:Now:Battery State".
     */
    private boolean evaluateSelfExpression(String expression, JSONObject taskDene, JSONObject pulse) {
        try {
            JexlContext context = new MapContext();
            JSONArray words = taskDene.getJSONArray("DeneWords");
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                if (!TeleonomeConstants.DENEWORD_TYPE_CEREBELLUM_TASK_CONDITION_POINTER.equals(
                        word.optString(TeleonomeConstants.DENEWORD_DENEWORD_TYPE_ATTRIBUTE))) {
                    continue;
                }
                String varName = word.getString(TeleonomeConstants.DENEWORD_NAME_ATTRIBUTE);
                String[] tokens = word.getString(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE)
                        .replace("@", "").split(":");
                if (tokens.length != 4) {
                    logger.warn("Self Task Condition Pointer '" + word.getString(TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE)
                            + "' is not a full Nucleus:DeneChain:Dene:DeneWord path, skipping");
                    continue;
                }
                Identity identity = new Identity(teleonomeName, tokens[0], tokens[1], tokens[2], tokens[3]);
                Object value = DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
                if (value != null) context.set(varName, coerceJexlValue(value));
            }
            Expression expr = jexl.createExpression(expression);
            Object result = expr.evaluate(context);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            logger.warn("Self expression evaluation error for '" + expression + "': " + e.getMessage());
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
        if (TeleonomeConstants.DENEWORD_CEREBELLUM_EXECUTION_FREQUENCY_EVERY_PULSE.equals(frequency)) {
            return "Pulse";
        }
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
            
            Calendar sunsetCalendar = new SunriseSunsetCalculator(
                    new Location(String.valueOf(lat), String.valueOf(lon)), TIMEZONE)
                    .getOfficialSunsetCalendarForDate(now);;
                 String sunsetTimestamp=	 sunsetCalendar.get(Calendar.HOUR_OF_DAY) + "#" + sunsetCalendar.get(Calendar.MINUTE) + "#"  + sunsetCalendar.get(Calendar.SECOND);
					
            logger.debug("line 470, sunsetTimestamp=" + sunsetTimestamp);
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
        if (trackingKey.endsWith(":Pulse")) return true;
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
                            return word.getDouble("Value");
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
                        .getConstructor(String.class, String.class)
                        .newInstance(teleonomeName, deviceName);
                t.setQueryHandler(this::queryHippocampus);
                logger.info("Instantiated task: " + className + " for device: " + deviceName);
                return t;
            } catch (Exception e) {
                logger.warn("Failed to instantiate task " + className + ": " + e.getMessage());
                return null;
            }
        });
    }

    // ── DeneChain builder & broadcast ─────────────────────────────────────────

    private Map<String, JSONArray> mergeLatestTaskWords() {
        Map<String, JSONArray> merged = new LinkedHashMap<>();
        for (Map.Entry<String, JSONArray> entry : latestTaskWords.entrySet()) {
            String deviceType = entry.getKey().split("\\|")[0];
            JSONArray bucket = merged.computeIfAbsent(deviceType, k -> new JSONArray());
            JSONArray words = entry.getValue();
            for (int i = 0; i < words.length(); i++) bucket.put(words.get(i));
        }
        return merged;
    }

    private JSONObject buildCerebellumDeneChain(Map<String, JSONArray> deviceWords, long epochSec) {
        JSONObject deneChain = new JSONObject();
        deneChain.put("Name", TeleonomeConstants.DENECHAIN_PURPOSE_CEREBELLUM);
        deneChain.put("Seconds Time", epochSec);
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

    // ── Emergency Channel ─────────────────────────────────────────────────────

    // One-shot alert banner in teleonomewebapp, distinct from the ongoing
    // per-cycle status broadcast (broadcastAnalysis) - each call creates a new,
    // separate banner entry there rather than overwriting a "current status", so
    // callers must only invoke this on a state transition worth an operator's
    // attention (e.g. a telepathon newly going missing), never every cycle a
    // condition remains true. See TelepathonRegistryTask.EMERGENCY_ALERT_WORD_NAME
    // for how a Task flags one of these without needing direct MQTT access itself.
    private void publishEmergency(String sender, String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("Sender", sender);
            payload.put("Message", message);
            payload.put("TimestampMillis", System.currentTimeMillis());

            MqttMessage mqttMessage = new MqttMessage(payload.toString().getBytes());
            mqttMessage.setQos(TeleonomeConstants.HEART_QUALITY_OF_SERVICE);
            mqttMessage.setRetained(false);
            client.publish(TeleonomeConstants.HEART_TOPIC_EMERGENCY_CHANNEL, mqttMessage);
            logger.info("Emergency published to " + TeleonomeConstants.HEART_TOPIC_EMERGENCY_CHANNEL + ": " + message);
        } catch (Exception e) {
            logger.warn("publishEmergency failed: " + Utils.getStringException(e));
        }
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

    // ── Registry sweep thread ────────────────────────────────────────────────────

    private class RegistrySweepThread extends Thread {
        RegistrySweepThread() { setDaemon(true); setName("CerebellumRegistrySweep"); }

        @Override
        public void run() {
            while (true) {
                try { Thread.sleep(REGISTRY_SWEEP_INTERVAL_MS); } catch (InterruptedException e) { e.printStackTrace(); }
                try {
                    runRegistrySweep();
                } catch (Exception e) {
                    logger.warn("RegistrySweepThread: sweep failed: " + Utils.getStringException(e));
                }
            }
        }
    }

    // teleonomeName is only known after absorbPulse() sees its first Status pulse
    // (see line ~237) - nothing to sweep against until then.
    private void runRegistrySweep() throws Exception {
        if (teleonomeName == null) {
            logger.debug("RegistrySweepThread: teleonomeName not yet known, skipping this cycle");
            return;
        }
        long nowEpochSec = System.currentTimeMillis() / 1000;
        JSONArray words = new TelepathonRegistryTask(teleonomeName, "TelepathonRegistrySweep").sweep(nowEpochSec);
        if (words.length() == 0) return;

        // Emergency-alert words are a one-shot event for publishEmergency(), not
        // part of the ongoing status broadcast - pull them out before merging
        // into latestTaskWords (see TelepathonRegistryTask.EMERGENCY_ALERT_WORD_NAME).
        JSONArray statusWords = new JSONArray();
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i);
            if (TelepathonRegistryTask.EMERGENCY_ALERT_WORD_NAME.equals(word.optString("Name"))) {
                publishEmergency("Cerebellum", word.optString("Value"));
            } else {
                statusWords.put(word);
            }
        }

        // Synthetic Dene name, distinct from any real device - see
        // TelepathonRegistryTask.sweep()'s javadoc for why this can't be attached
        // to a specific device the way pulse-triggered Task output is.
        latestTaskWords.put("Telepathon Registry|TelepathonRegistryTask", statusWords);
        broadcastAnalysis(buildCerebellumDeneChain(mergeLatestTaskWords(), nowEpochSec));
    }
}
