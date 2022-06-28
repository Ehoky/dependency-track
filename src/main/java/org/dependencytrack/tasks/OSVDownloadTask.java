package org.dependencytrack.tasks;

import alpine.common.logging.Logger;
import alpine.event.framework.Event;
import alpine.event.framework.LoggableSubscriber;
import alpine.model.ConfigProperty;
import kong.unirest.json.JSONObject;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.dependencytrack.common.HttpClientPool;
import org.dependencytrack.event.GoogleOSVMirrorEvent;
import org.dependencytrack.event.IndexEvent;
import org.dependencytrack.model.Cwe;
import org.dependencytrack.model.Severity;
import org.dependencytrack.model.Vulnerability;
import org.dependencytrack.model.VulnerableSoftware;
import org.dependencytrack.parser.common.resolver.CweResolver;
import org.dependencytrack.parser.osv.GoogleOSVAdvisoryParser;
import org.dependencytrack.parser.osv.model.Ecosystem;
import org.dependencytrack.parser.osv.model.OSVAdvisory;
import org.dependencytrack.parser.osv.model.OSVVulnerability;
import org.dependencytrack.persistence.QueryManager;

import us.springett.cvss.Cvss;
import us.springett.cvss.Score;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.dependencytrack.model.ConfigPropertyConstants.VULNERABILITY_SOURCE_GOOGLE_OSV_ENABLED;
import static org.dependencytrack.model.Severity.getSeverityByLevel;
import static org.dependencytrack.util.VulnerabilityUtil.*;

public class OSVDownloadTask implements LoggableSubscriber {

    private static final Logger LOGGER = Logger.getLogger(OSVDownloadTask.class);
    private final boolean isEnabled;
    private HttpUriRequest request;

    public OSVDownloadTask() {
        try (final QueryManager qm = new QueryManager()) {
            final ConfigProperty enabled = qm.getConfigProperty(VULNERABILITY_SOURCE_GOOGLE_OSV_ENABLED.getGroupName(), VULNERABILITY_SOURCE_GOOGLE_OSV_ENABLED.getPropertyName());
            this.isEnabled = enabled != null && Boolean.valueOf(enabled.getPropertyValue());
        }
    }

    @Override
    public void inform(Event e) {

        if (e instanceof GoogleOSVMirrorEvent && this.isEnabled) {

            for (Ecosystem ecosystem : Ecosystem.values()) {
                LOGGER.info("Updating datasource with Google OSV advisories for ecosystem " + ecosystem.getValue());
                String url = "https://osv-vulnerabilities.storage.googleapis.com/" + ecosystem.getValue() + "/all.zip";
                request = new HttpGet(url);
                request.setHeader("Accept", "*/*");
                request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");
                try (final CloseableHttpResponse response = HttpClientPool.getClient().execute(request)) {
                    final StatusLine status = response.getStatusLine();
                    if (status.getStatusCode() == 200) {
                        try (InputStream in = response.getEntity().getContent();
                            ZipInputStream zipInput = new ZipInputStream(in)) {
                            unzipFolder(zipInput);
                        }
                    } else {
                        LOGGER.error("Download failed : " + status.getStatusCode() + ": " + status.getReasonPhrase());
                    }
                } catch (Exception ex) {
                    LOGGER.error("Exception while executing Http client request", ex);
                }
            }
        }
    }

    private void unzipFolder(ZipInputStream zipIn) throws IOException {

        BufferedReader reader = new BufferedReader(new InputStreamReader(zipIn));
        GoogleOSVAdvisoryParser parser = new GoogleOSVAdvisoryParser();
        ZipEntry zipEntry = zipIn.getNextEntry();
        while (zipEntry != null) {

            String line = null;
            StringBuilder out = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
            JSONObject json = new JSONObject(out.toString());
            final OSVAdvisory osvAdvisory = parser.parse(json);
            if (osvAdvisory != null) {
                updateDatasource(osvAdvisory);
            }
            zipEntry = zipIn.getNextEntry();
            reader = new BufferedReader(new InputStreamReader(zipIn));
        }
        reader.close();
    }

    public void updateDatasource(final OSVAdvisory advisory) {

        try (QueryManager qm = new QueryManager()) {

            LOGGER.debug("Synchronizing Google OSV advisory: " + advisory.getId());
            final Vulnerability synchronizedVulnerability = qm.synchronizeVulnerability(mapAdvisoryToVulnerability(qm, advisory), false);
            final List<VulnerableSoftware> vsList = new ArrayList<>();
            for (OSVVulnerability osvVulnerability: advisory.getVulnerabilities()) {
                VulnerableSoftware vs = mapVulnerabilityToVulnerableSoftware(qm, osvVulnerability);
                if (vs != null) {
                    vsList.add(vs);
                }
            }
            LOGGER.debug("Updating vulnerable software for OSV advisory: " + advisory.getId());
            qm.persist(vsList);
            synchronizedVulnerability.setVulnerableSoftware(vsList);
            qm.persist(synchronizedVulnerability);
        }
        Event.dispatch(new IndexEvent(IndexEvent.Action.COMMIT, Vulnerability.class));
    }

    public Vulnerability mapAdvisoryToVulnerability(final QueryManager qm, final OSVAdvisory advisory) {

        final Vulnerability vuln = new Vulnerability();
        if(advisory.getId() != null) {
            vuln.setSource(extractSource(advisory.getId()));
        }
        vuln.setVulnId(String.valueOf(advisory.getId()));
        vuln.setTitle(advisory.getSummary());
        vuln.setDescription(advisory.getDetails());
        vuln.setPublished(Date.from(advisory.getPublished().toInstant()));
        vuln.setUpdated(Date.from(advisory.getModified().toInstant()));

        if (advisory.getCredits() != null) {
            vuln.setCredits(String.join(", ", advisory.getCredits()));
        }

        if (advisory.getReferences() != null && advisory.getReferences().size() > 0) {
            final StringBuilder sb = new StringBuilder();
            for (String ref : advisory.getReferences()) {
                sb.append("* [").append(ref).append("](").append(ref).append(")\n");
            }
            vuln.setReferences(sb.toString());
        }

        if (advisory.getCweIds() != null) {
            for (int i=0; i<advisory.getCweIds().size(); i++) {
                final Cwe cwe = CweResolver.getInstance().resolve(qm, advisory.getCweIds().get(i));
                if (cwe != null) {
                    vuln.addCwe(cwe);
                }
            }
        }
        vuln.setSeverity(calculateOSVSeverity(advisory));
        vuln.setCvssV2Vector(advisory.getCvssV2Vector());
        vuln.setCvssV3Vector(advisory.getCvssV3Vector());
        return vuln;
    }

    // calculate severity of vulnerability on priority-basis (database, ecosystem)
    public Severity calculateOSVSeverity(OSVAdvisory advisory) {

        // derive from database_specific cvss v3 vector if available
        if(advisory.getCvssV3Vector() != null) {
            Cvss cvss = Cvss.fromVector(advisory.getCvssV3Vector());
            Score score = cvss.calculateScore();
            return normalizedCvssV3Score(score.getBaseScore());
        }
        // derive from database_specific cvss v2 vector if available
        if (advisory.getCvssV2Vector() != null) {
            Cvss cvss = Cvss.fromVector(advisory.getCvssV2Vector());
            Score score = cvss.calculateScore();
            return normalizedCvssV2Score(score.getBaseScore());
        }
        // get database_specific severity string if available
        if (advisory.getSeverity() != null) {
            if (advisory.getSeverity().equalsIgnoreCase("CRITICAL")) {
                return Severity.CRITICAL;
            } else if (advisory.getSeverity().equalsIgnoreCase("HIGH")) {
                return Severity.HIGH;
            } else if (advisory.getSeverity().equalsIgnoreCase("MODERATE")) {
                return Severity.MEDIUM;
            } else if (advisory.getSeverity().equalsIgnoreCase("LOW")) {
                return Severity.LOW;
            }
        }
        // get largest ecosystem_specific severity from its affected packages
        if (advisory.getVulnerabilities() != null) {
            List<Integer> severityLevels = new ArrayList<>();
            for (OSVVulnerability vuln : advisory.getVulnerabilities()) {
                severityLevels.add(vuln.getSeverity().getLevel());
            }
            Collections.sort(severityLevels);
            Collections.reverse(severityLevels);
            return getSeverityByLevel(severityLevels.get(0));
        }
        return Severity.UNASSIGNED;
    }

    public Vulnerability.Source extractSource(String vulnId) {

        final String sourceId = vulnId.split("-")[0];
        switch (sourceId) {
            case "GHSA": return Vulnerability.Source.GITHUB;
            case "CVE": return Vulnerability.Source.NVD;
            default: return Vulnerability.Source.GOOGLE;
        }
    }

    private VulnerableSoftware mapVulnerabilityToVulnerableSoftware(final QueryManager qm, final OSVVulnerability vuln) {

        String versionStartIncluding = vuln.getLowerVersionRange();
        String versionEndExcluding = vuln.getUpperVersionRange();

        final String purl = vuln.getPurl();
        if (purl == null) return null;

        VulnerableSoftware vs = qm.getVulnerableSoftwareByPurl(vuln.getPurl(), versionEndExcluding, versionStartIncluding);
        if (vs != null) {
            return vs;
        }
        vs = new VulnerableSoftware();
        vs.setVulnerable(true);
        vs.setPurlType(vuln.getPackageEcosystem());
        vs.setPurl(vuln.getPurl());
        vs.setVersionStartIncluding(versionStartIncluding);
        vs.setVersionEndExcluding(versionEndExcluding);
        return vs;
    }
}