package com.open.spring.mvc.calendar;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the calendar_events table: a class event, an appointment a student
 * booked, or a break day. It is a POJO (fields, constructors, getters and setters)
 * that @Entity maps to the table, so JPA can save and load it.
 *
 * The API never sends this class as JSON. CalendarEventApiRestController copies it
 * into its EventView record, so the table can gain columns without changing the API.
 * The table is initialized by CalendarEventTableInitializer (ddl-auto is none).
 */
@Entity
@Table(name = "calendar_events")
public class CalendarEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;
    private String title;
    private String description;
    private String type = "event"; // Event type: "event" or "appointment". Default: "event"
    private String period; // New field for the period (e.g., Period 1, Period 3)
    private String classPeriod; // For appointments: "P1", "P2", "P3", "P4", "P5", or empty
    private String groupName; // For appointments: freeform text
    private String individual; // For appointments: name of user who created the appointment
    
    // Break-specific fields
    private boolean isBreak = false; // Flag to indicate if this is a break event
    private String name; // Break name (e.g., "Winter Break") - used when isBreak is true

    // Default constructor
    public CalendarEventEntity() {
    }

    // Constructor with fields
    public CalendarEventEntity(LocalDate date, String title, String description, String type, String period) {
        this.date = date;
        this.title = title;
        this.description = description;
        this.type = type != null ? type : "event";
        this.period = period; // Initialize period
        this.isBreak = false;
    }
    
    // Constructor for break events
    public CalendarEventEntity(LocalDate date, String name, String description, boolean isBreak) {
        this.date = date;
        this.name = name;
        this.description = description;
        this.isBreak = isBreak;
        if (isBreak) {
            this.title = name; // Use name as title for breaks
            this.type = "break";
        }
    }

    // Constructor with all appointment fields
    public CalendarEventEntity(LocalDate date, String title, String description, String type, String period,
                         String classPeriod, String groupName, String individual) {
        this.date = date;
        this.title = title;
        this.description = description;
        this.type = type != null ? type : "event";
        this.period = period;
        this.classPeriod = classPeriod;
        this.groupName = groupName;
        this.individual = individual;
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getClassPeriod() {
        return classPeriod;
    }

    public void setClassPeriod(String classPeriod) {
        this.classPeriod = classPeriod;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getIndividual() {
        return individual;
    }

    public void setIndividual(String individual) {
        this.individual = individual;
    }

    // Break-specific getters and setters
    public boolean isBreak() {
        return isBreak;
    }

    public void setBreak(boolean isBreak) {
        this.isBreak = isBreak;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
