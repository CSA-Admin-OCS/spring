package com.open.spring.mvc.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List; 

@Repository
public interface AnnouncementJpaRepository extends JpaRepository<AnnouncementEntity, Long> {
    AnnouncementEntity findByAuthor(String author); // Method to find announcement by author

    List<AnnouncementEntity> findByTags(String tags); // Example method to find announcements by tags

    AnnouncementEntity findByTitle(String title); // Example method to find announcements by title

    List<AnnouncementEntity> findAllByOrderByTimestampDesc();
}
