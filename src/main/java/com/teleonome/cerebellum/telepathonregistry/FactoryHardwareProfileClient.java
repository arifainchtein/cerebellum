package com.teleonome.cerebellum.telepathonregistry;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

/**
 * Purpose: pull the real per-unit hardware profile (PCB boards, battery,
 * panel) from Factory's GetHardwareProfileBySerialNumber endpoint, so
 * TelepathonRegistryTask.ensureProfileDefaults() can seed telepathon_profile
 * with real Factory-known data instead of falling straight to the coarse
 * per-Device-Type-Id hardcoded fallback.
 *
 * Endpoint contract agreed with Factory (see
 * reports/CerebellumReply_HardwareProfileEndpointSpec_2026-08-02.md in the
 * FactorySystem repo for the full spec sent back to them) - NOT YET
 * IMPLEMENTED on the Factory side as of 2026-08-02. This class is written
 * against the agreed contract so both sides can move in parallel; until
 * Factory ships the endpoint every call below either times out or 404s, and
 * ensureProfileDefaults() falls through to the hardcoded default exactly as
 * it did before this class existed.
 *
 *   GET {factoryUrl}/FactoryServlet?formName=GetHardwareProfileBySerialNumber&serialNumber={serial}
 *   200 -> {"pcbBoards": "Wally Build 17, Daffodil Build 8",
 *           "batteryType": "LiFePO4 1S",
 *           "batteryCapacityMah": 600.0,   // nullable
 *           "panelWatts": null}            // nullable
 *   404 -> serial not known to Factory (not yet provisioned there, or
 *          provisioned before Factory started capturing this on 2026-08-01)
 *
 * factoryUrl is read from lib/app.properties (key "factoryUrl"), same file
 * and directory convention as the Postgres port/pwd (see BACKGROUND.md).
 * Absent/blank key disables this client (isEnabled() false) so deployments
 * that haven't added the key keep working exactly as before.
 *
 * Any failure (disabled, network error, non-200/404 status, malformed JSON)
 * returns null - the caller's hardcoded fallback is the safety net, not this
 * class, so failures here are logged and swallowed, never thrown.
 */
class FactoryHardwareProfileClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final Logger logger = Logger.getLogger(getClass());
    private final String factoryUrl;
    private final HttpClient httpClient;

    FactoryHardwareProfileClient() {
        this.factoryUrl = loadFactoryUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    boolean isEnabled() {
        return factoryUrl != null && !factoryUrl.isEmpty();
    }

    /** Returns null on any failure or when disabled - see class javadoc. */
    TelepathonRegistryTask.DeviceProfileDefault fetch(String serialNumber) {
        if (!isEnabled()) return null;
        try {
            String url = factoryUrl + "/FactoryServlet?formName=GetHardwareProfileBySerialNumber&serialNumber="
                    + URLEncoder.encode(serialNumber, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                logger.debug("FactoryHardwareProfileClient: serial='" + serialNumber + "' not known to Factory");
                return null;
            }
            if (response.statusCode() != 200) {
                logger.warn("FactoryHardwareProfileClient: unexpected status " + response.statusCode()
                        + " for serial='" + serialNumber + "'");
                return null;
            }

            JSONObject body = new JSONObject(response.body());
            String pcbBoards = body.optString("pcbBoards", null);
            String batteryType = body.optString("batteryType", null);
            Double batteryCapacityMah = body.has("batteryCapacityMah") && !body.isNull("batteryCapacityMah")
                    ? body.getDouble("batteryCapacityMah") : null;
            Double panelWatts = body.has("panelWatts") && !body.isNull("panelWatts")
                    ? body.getDouble("panelWatts") : null;

            return new TelepathonRegistryTask.DeviceProfileDefault(pcbBoards, batteryType, batteryCapacityMah, panelWatts);
        } catch (Exception e) {
            logger.warn("FactoryHardwareProfileClient: lookup failed for serial='" + serialNumber + "': " + e.getMessage());
            return null;
        }
    }

    private String loadFactoryUrl() {
        try (FileInputStream in = new FileInputStream("lib/app.properties")) {
            Properties prop = new Properties();
            prop.load(in);
            String url = prop.getProperty("factoryUrl");
            if (url != null) url = url.trim();
            return (url == null || url.isEmpty()) ? null : url.replaceAll("/+$", "");
        } catch (Exception e) {
            return null;
        }
    }
}
