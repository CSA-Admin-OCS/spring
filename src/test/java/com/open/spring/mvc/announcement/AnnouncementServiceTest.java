package com.open.spring.mvc.announcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AnnouncementServiceTest {

    @Mock
    private AnnouncementJpaRepository repository;

    private AnnouncementService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AnnouncementService(repository);
        when(repository.save(any(AnnouncementEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createSavesTheFieldsWithATimestamp() {
        AnnouncementEntity saved = service.create("Ada", "Quiz Friday", "Unit 3 quiz", "csa");

        assertEquals("Ada", saved.getAuthor());
        assertEquals("Quiz Friday", saved.getTitle());
        assertEquals("Unit 3 quiz", saved.getBody());
        assertEquals("csa", saved.getTags());
        assertNotNull(saved.getTimestamp());
        verify(repository).save(saved);
    }

    @Test
    void findAllReturnsEveryAnnouncement() {
        List<AnnouncementEntity> all = List.of(announcement("One"), announcement("Two"));
        when(repository.findAll()).thenReturn(all);

        assertSame(all, service.findAll());
    }

    @Test
    void editBodyReplacesTheBodyAndSaves() {
        AnnouncementEntity existing = announcement("Quiz Friday");
        when(repository.findByTitle("Quiz Friday")).thenReturn(existing);

        AnnouncementEntity edited = service.editBody("Quiz Friday", "Moved to Monday");

        assertSame(existing, edited);
        assertEquals("Moved to Monday", edited.getBody());
        verify(repository).save(existing);
    }

    @Test
    void editBodyOfAMissingTitleThrowsAsItDidBeforeTheService() {
        when(repository.findByTitle("Missing")).thenReturn(null);

        // The API turns this into a 500; see Known issues in docs/announcement-architecture.md.
        assertThrows(NullPointerException.class, () -> service.editBody("Missing", "x"));
        verify(repository, never()).save(any());
    }

    @Test
    void deleteByTitleDeletesTheMatchingAnnouncement() {
        AnnouncementEntity existing = announcement("Quiz Friday");
        when(repository.findByTitle("Quiz Friday")).thenReturn(existing);

        service.deleteByTitle("Quiz Friday");

        verify(repository).delete(existing);
    }

    @Test
    void findByAuthorReturnsNullWhenThereIsNone() {
        when(repository.findByAuthor("Nobody")).thenReturn(null);

        assertNull(service.findByAuthor("Nobody"));
    }

    @Test
    void findByTagsAndNewestFirstUseTheirQueries() {
        List<AnnouncementEntity> tagged = List.of(announcement("Tagged"));
        List<AnnouncementEntity> newest = List.of(announcement("Newest"));
        when(repository.findByTags("csa")).thenReturn(tagged);
        when(repository.findAllByOrderByTimestampDesc()).thenReturn(newest);

        assertSame(tagged, service.findByTags("csa"));
        assertSame(newest, service.findAllNewestFirst());
    }

    private static AnnouncementEntity announcement(String title) {
        return new AnnouncementEntity("Ada", title, "body", "csa");
    }
}
