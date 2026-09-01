package com.open.spring.mvc.capstone;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps capstone_project in step with the Jekyll capstone posts, automatically.
 *
 * The posts are the source of truth. Jekyll publishes them as /capstone/projects.json
 * on every site build, and this pulls that manifest on startup and on a timer, so
 * adding or renaming a capstone post is the only step -- the row an admin needs in
 * order to attach a mentor appears on its own.
 *
 * Upserts by slug and never deletes: a slug vanishing from the manifest (a renamed
 * post, a half-finished build, a site that briefly 404s) must not silently drop that
 * project's mentor assignments. Removing a project stays a deliberate act.
 *
 * Failure is non-fatal by design. This runs at startup, and a site that is down or a
 * machine with no network must not stop the backend from booting.
 */
@Service
public class CapstoneSyncService {

    private static final Logger logger = LoggerFactory.getLogger(CapstoneSyncService.class);

    @Autowired
    private CapstoneProjectJpaRepository capstoneRepository;

    /** Override in .env to point at a local Jekyll server during development. */
    @Value("${capstone.manifest.url:https://pages.opencodingsociety.com/capstone/projects.json}")
    private String manifestUrl;

    @Value("${capstone.sync.enabled:true}")
    private boolean enabled;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        syncQuietly("startup");
    }

    // Every 30 minutes. The manifest only changes when the site is rebuilt, so this is
    // about eventual consistency, not freshness.
    @Scheduled(fixedDelay = 1_800_000L, initialDelay = 1_800_000L)
    public void syncOnSchedule() {
        syncQuietly("scheduled");
    }

    private void syncQuietly(String trigger) {
        if (!enabled) {
            return;
        }
        try {
            SyncResult result = sync();
            if (result.created > 0 || result.updated > 0) {
                logger.info("capstone_sync trigger={} created={} updated={} total={}",
                        trigger, result.created, result.updated, result.total);
            }
        } catch (Exception e) {
            // Non-fatal on purpose -- see the class comment.
            logger.warn("capstone_sync_failed trigger={} url={} reason={}",
                    trigger, manifestUrl, e.getMessage());
        }
    }

    public static class SyncResult {
        public int created;
        public int updated;
        public long total;
    }

    @Transactional
    public SyncResult sync() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(manifestUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("manifest returned HTTP " + response.statusCode());
        }

        JSONArray incoming = new JSONArray(response.body());
        SyncResult result = new SyncResult();

        for (int i = 0; i < incoming.length(); i++) {
            JSONObject item = incoming.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String slug = item.optString("slug", "").trim();
            if (slug.isEmpty()) {
                continue;
            }
            String title = item.optString("title", slug);
            String description = item.optString("description", "");
            String url = item.optString("url", "/capstone/" + slug + "/");

            Optional<CapstoneProject> existing = capstoneRepository.findBySlug(slug);
            if (existing.isPresent()) {
                CapstoneProject project = existing.get();
                // Only write when something actually changed, so an unchanged manifest
                // does not churn the table on every poll.
                if (!equalsSafe(project.getTitle(), title)
                        || !equalsSafe(project.getDescription(), description)
                        || !equalsSafe(project.getUrl(), url)) {
                    project.setTitle(title);
                    project.setDescription(description);
                    project.setUrl(url);
                    capstoneRepository.save(project);
                    result.updated++;
                }
            } else {
                capstoneRepository.save(new CapstoneProject(slug, title, description, url));
                result.created++;
            }
        }

        result.total = capstoneRepository.count();
        return result;
    }

    private boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
