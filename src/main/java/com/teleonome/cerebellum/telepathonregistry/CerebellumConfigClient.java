package com.teleonome.cerebellum.telepathonregistry;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purpose: read TelepathonRegistryTask's temporary-list/promotion/lateness config
 * values - "Days in Temporary List", "Promotion Threshold", and "Days Before Stale" -
 * straight from the live denome, per Internal:Cerebellum:Configuration (the
 * Configuration Dene already exists there under every host's live Teleonome.denome -
 * add the three DeneWords below to it, no version-controlled template, same "wire it
 * into the live file" convention as every other Cerebellum Task Dene in this repo):
 *
 *   { "Name": "Days in Temporary List", "Value": 7, "Value Type": "int", "Required": true }
 *   { "Name": "Promotion Threshold",    "Value": 3, "Value Type": "int", "Required": true }
 *   { "Name": "Days Before Stale",      "Value": 2, "Value Type": "int", "Required": true }
 *
 * "Days Before Stale" is unrelated to the other two - those govern promoting a *new,
 * unconfirmed* identity into the official registry (telepathon_registry_pending), while
 * "Days Before Stale" governs how long an *already-official* device can go silent
 * before evaluateDeviceStatuses() calls it "Stale" rather than merely "Late" (see
 * TelepathonRegistryTask.thresholdsFor()). Kept in the same Configuration Dene for
 * discoverability, but deliberately a separate DeneWord rather than reusing Promotion
 * Threshold/Days in Temporary List, which would conflate two independent policies.
 *
 * TelepathonRegistryTask.process()/sweep() have no direct access to the full pulse
 * (Task.process() is only ever handed the one Telepathon's DeneChain - see Task.java),
 * so this fetches the teleonome's own denome the same way a human would per the task
 * description this class was written against: {@code curl
 * http://<teleonomeName>.local/Teleonome.denome}. Host is derived as
 * {@code teleonomeName.toLowerCase() + ".local"} (e.g. "ChinampaMonitor" ->
 * "chinampamonitor.local") - mDNS hostname Annabelle/the web server already answers to.
 *
 * Cached per teleonome name for CACHE_TTL_MILLIS so a hot pulse loop doesn't do a
 * blocking HTTP GET on every single pulse (same reasoning as
 * FactoryHardwareProfileClient not re-querying Factory on every pulse). Any failure
 * (unreachable host, non-200, malformed JSON, missing Dene/DeneWord) returns the
 * hardcoded defaults below and logs at debug - a config-fetch failure must never
 * block registry reconciliation.
 */
class CerebellumConfigClient {

    static final int DEFAULT_DAYS_IN_TEMPORARY_LIST = 7;
    static final int DEFAULT_PROMOTION_THRESHOLD = 3;
    static final int DEFAULT_DAYS_BEFORE_STALE = 2;

    static class RegistryConfig {
        final int daysInTemporaryList;
        final int promotionThreshold;
        final int daysBeforeStale;
        RegistryConfig(int daysInTemporaryList, int promotionThreshold, int daysBeforeStale) {
            this.daysInTemporaryList = daysInTemporaryList;
            this.promotionThreshold = promotionThreshold;
            this.daysBeforeStale = daysBeforeStale;
        }
    }

    private static final RegistryConfig DEFAULTS =
            new RegistryConfig(DEFAULT_DAYS_IN_TEMPORARY_LIST, DEFAULT_PROMOTION_THRESHOLD, DEFAULT_DAYS_BEFORE_STALE);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final long CACHE_TTL_MILLIS = 5 * 60_000;

    private final Logger logger = Logger.getLogger(getClass());
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final Map<String, RegistryConfig> cachedConfig = new ConcurrentHashMap<>();
    private final Map<String, Long> cachedAtMillis = new ConcurrentHashMap<>();

    RegistryConfig get(String teleonomeName) {
        Long cachedAt = cachedAtMillis.get(teleonomeName);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MILLIS) {
            return cachedConfig.getOrDefault(teleonomeName, DEFAULTS);
        }
        RegistryConfig config = fetch(teleonomeName);
        cachedConfig.put(teleonomeName, config);
        cachedAtMillis.put(teleonomeName, System.currentTimeMillis());
        return config;
    }

    private RegistryConfig fetch(String teleonomeName) {
        try {
            String host = teleonomeName.toLowerCase().replaceAll("\\s+", "") + ".local";
            String url = "http://" + host + "/Teleonome.denome";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.debug("CerebellumConfigClient: status " + response.statusCode()
                        + " fetching denome for '" + teleonomeName + "', using defaults");
                return DEFAULTS;
            }

            JSONObject root = new JSONObject(response.body());
            JSONObject denome = root.has("Denome") ? root.getJSONObject("Denome") : root;
            JSONObject configurationDene = findCerebellumConfigurationDene(denome);
            if (configurationDene == null) {
                logger.debug("CerebellumConfigClient: no Internal:Cerebellum:Configuration Dene for '"
                        + teleonomeName + "', using defaults");
                return DEFAULTS;
            }

            int days = readInt(configurationDene, "Days in Temporary List", DEFAULT_DAYS_IN_TEMPORARY_LIST);
            int threshold = readInt(configurationDene, "Promotion Threshold", DEFAULT_PROMOTION_THRESHOLD);
            int daysBeforeStale = readInt(configurationDene, "Days Before Stale", DEFAULT_DAYS_BEFORE_STALE);
            return new RegistryConfig(days, threshold, daysBeforeStale);
        } catch (Exception e) {
            logger.debug("CerebellumConfigClient: failed to fetch config for '" + teleonomeName
                    + "', using defaults: " + e.getMessage());
            return DEFAULTS;
        }
    }

    private JSONObject findCerebellumConfigurationDene(JSONObject denome) {
        JSONArray nuclei = denome.optJSONArray("Nuclei");
        if (nuclei == null) return null;
        for (int i = 0; i < nuclei.length(); i++) {
            JSONObject nucleus = nuclei.getJSONObject(i);
            if (!"Internal".equals(nucleus.optString("Name"))) continue;
            JSONArray deneChains = nucleus.optJSONArray("DeneChains");
            if (deneChains == null) return null;
            for (int j = 0; j < deneChains.length(); j++) {
                JSONObject deneChain = deneChains.getJSONObject(j);
                if (!"Cerebellum".equals(deneChain.optString("Name"))) continue;
                JSONArray denes = deneChain.optJSONArray("Denes");
                if (denes == null) return null;
                for (int k = 0; k < denes.length(); k++) {
                    JSONObject dene = denes.getJSONObject(k);
                    if ("Configuration".equals(dene.optString("Name"))) return dene;
                }
            }
        }
        return null;
    }

    private int readInt(JSONObject dene, String wordName, int defaultValue) {
        JSONArray words = dene.optJSONArray("DeneWords");
        if (words == null) return defaultValue;
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i);
            if (!wordName.equals(word.optString("Name"))) continue;
            try {
                return Integer.parseInt(word.get("Value").toString().trim());
            } catch (Exception e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
