package com.open.spring.mvc.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Registering JPA Repository and table contents
public interface CalendarEventJpaRepository extends JpaRepository<CalendarEventEntity, Long> {
    List<CalendarEventEntity> findByDate(LocalDate date); // Method to find calendar events by date
    List<CalendarEventEntity> findByDateBetween(LocalDate startDate, LocalDate endDate); // Method to find all calendar events between dates
    List<CalendarEventEntity> findAll();
    Optional<CalendarEventEntity> findByTitle(String title); // Optional is fine here
    Optional<CalendarEventEntity> findByTitleAndDate(String title, LocalDate date); // Find duplicate by title and date
    Optional<CalendarEventEntity> findByTitleAndDateAndIndividual(String title, LocalDate date, String individual);

    // Count appointments by date and classPeriod for validation
    @Query("SELECT COUNT(e) FROM CalendarEventEntity e WHERE e.date = :date AND e.classPeriod = :classPeriod AND e.type = 'appointment'")
    long countAppointmentsByDateAndClassPeriod(@Param("date") LocalDate date, @Param("classPeriod") String classPeriod);
    
    // Break-specific query methods
    List<CalendarEventEntity> findByIsBreakAndDate(boolean isBreak, LocalDate date); // Find breaks by date
    List<CalendarEventEntity> findByIsBreak(boolean isBreak); // Find all breaks
}
