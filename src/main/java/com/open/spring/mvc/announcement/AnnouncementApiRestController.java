package com.open.spring.mvc.announcement;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for announcements under /api/announcements.
 *
 * This class only translates HTTP into AnnouncementService calls and service results into
 * responses. The routes, parameters and status codes are a contract with existing clients,
 * such as the CS112 Fullstack lessons, so keep them unchanged. Access follows the shared
 * /api/** rule in SecurityConfig: any signed-in account.
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementApiRestController {

    /**
     * DTO the API returns for one announcement. Its components keep the order and names of
     * the entity's fields, so the JSON matches what this API returned before it had a DTO.
     */
    public record AnnouncementRecord(
            Long id,
            String author,
            String title,
            String body,
            String timestamp,
            String tags) {

        static AnnouncementRecord of(AnnouncementEntity entity) {
            return new AnnouncementRecord(entity.getId(), entity.getAuthor(), entity.getTitle(),
                    entity.getBody(), entity.getTimestamp(), entity.getTags());
        }

        static List<AnnouncementRecord> of(List<AnnouncementEntity> entities) {
            return entities.stream().map(AnnouncementRecord::of).toList();
        }
    }

    private final AnnouncementService announcementService;

    public AnnouncementApiRestController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    /** POST /api/announcements/create?author=&title=&body=&tags= - saves a new announcement. */
    @PostMapping("/create")
    public ResponseEntity<AnnouncementRecord> createAnnouncement(@RequestParam String author, @RequestParam String title,
            @RequestParam String body, @RequestParam String tags) {
        AnnouncementEntity saved = announcementService.create(author, title, body, tags);
        return new ResponseEntity<>(AnnouncementRecord.of(saved), HttpStatus.CREATED);
    }

    /** GET /api/announcements - every announcement. */
    @GetMapping
    public ResponseEntity<List<AnnouncementRecord>> getAllAnnouncements() {
        return new ResponseEntity<>(AnnouncementRecord.of(announcementService.findAll()), HttpStatus.OK);
    }

    /** POST /api/announcements/edit/{title} - replaces the body with the plain-text request body. */
    @PostMapping("/edit/{title}")
    public ResponseEntity<AnnouncementRecord> editAnnouncement(@PathVariable String title, @RequestBody String body) {
        AnnouncementEntity edited = announcementService.editBody(title, body);
        return new ResponseEntity<>(AnnouncementRecord.of(edited), HttpStatus.OK);
    }

    /** POST /api/announcements/delete/{title} - 204 when deleted, 500 when it could not be. */
    @PostMapping("/delete/{title}")
    public ResponseEntity<HttpStatus> deleteAnnouncement(@PathVariable String title) {
        try {
            announcementService.deleteByTitle(title);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** GET /api/announcements/author/{author} - the author's announcement, or 404. */
    @GetMapping("/author/{author}")
    public ResponseEntity<AnnouncementRecord> getAnnouncementByAuthor(@PathVariable String author) {
        AnnouncementEntity announcement = announcementService.findByAuthor(author);
        if (announcement != null) {
            return new ResponseEntity<>(AnnouncementRecord.of(announcement), HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    /** GET /api/announcements/tags/{tags} - announcements whose tags equal this value. */
    @GetMapping("/tags/{tags}")
    public ResponseEntity<List<AnnouncementRecord>> getAnnouncementsByTags(@PathVariable String tags) {
        return new ResponseEntity<>(AnnouncementRecord.of(announcementService.findByTags(tags)), HttpStatus.OK);
    }

    /** GET /api/announcements/orderedByTime - every announcement, newest first. */
    @GetMapping("/orderedByTime")
    public ResponseEntity<List<AnnouncementRecord>> getAllAnnouncementsOrderedByTime() {
        return new ResponseEntity<>(AnnouncementRecord.of(announcementService.findAllNewestFirst()), HttpStatus.OK);
    }
}
