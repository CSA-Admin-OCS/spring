package com.open.spring.mvc.announcement;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Business rules for announcements: creating, reading, editing and deleting them.
 *
 * The REST controller translates HTTP into calls on this service, and this service is the
 * only class that uses AnnouncementJpaRepository. Edit and delete find an announcement by
 * its title; a missing title fails the same way it did before the service existed (see
 * docs/announcement-architecture.md, Known issues).
 */
@Service
public class AnnouncementService {

    private final AnnouncementJpaRepository repository;

    public AnnouncementService(AnnouncementJpaRepository repository) {
        this.repository = repository;
    }

    /** Saves a new announcement stamped with the current time and returns the saved row. */
    public AnnouncementEntity create(String author, String title, String body, String tags) {
        return repository.save(new AnnouncementEntity(author, title, body, tags));
    }

    /** Returns every announcement in the order the database stores them. */
    public List<AnnouncementEntity> findAll() {
        return repository.findAll();
    }

    /** Replaces the body of the announcement with this title and returns it. */
    public AnnouncementEntity editBody(String title, String body) {
        AnnouncementEntity announcement = repository.findByTitle(title);
        announcement.setBody(body);
        repository.save(announcement);
        return announcement;
    }

    /** Deletes the announcement with this title. */
    public void deleteByTitle(String title) {
        AnnouncementEntity announcement = repository.findByTitle(title);
        repository.delete(announcement);
    }

    /** Returns the announcement by this author, or null when there is none. */
    public AnnouncementEntity findByAuthor(String author) {
        return repository.findByAuthor(author);
    }

    /** Returns the announcements whose tags field equals this value exactly. */
    public List<AnnouncementEntity> findByTags(String tags) {
        return repository.findByTags(tags);
    }

    /** Returns every announcement, newest timestamp first. */
    public List<AnnouncementEntity> findAllNewestFirst() {
        return repository.findAllByOrderByTimestampDesc();
    }
}
