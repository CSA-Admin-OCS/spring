package com.open.spring.mvc.announcement;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "Announcement")
@Table(name = "announcement")
public class AnnouncementEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(unique=false)
    private String author;
    private String title;
    private String body;
    private String timestamp;
    private String tags;

    // Define a formatter for the timestamp
    private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Constructor with necessary fields
    public AnnouncementEntity(String author, String title, String body, String tags) {
        this.author = author;
        this.title = title;
        this.body = body;
        this.tags = tags;
        this.timestamp = LocalDateTime.now().format(formatter);
    }

    // Static method to create initial data
    public static List<AnnouncementEntity> createInitialData() {
        List<AnnouncementEntity> announcements = new ArrayList<>();

        // Create announcements with formatted timestamp
        announcements.add(new AnnouncementEntity("Finn", "Test of innit", "Don't mind this message", "welcome"));

        return announcements;
    }

    // Static method to initialize the data
    public static List<AnnouncementEntity> init() {
        return createInitialData();
    }
}
