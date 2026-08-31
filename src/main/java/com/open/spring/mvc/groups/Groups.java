package com.open.spring.mvc.groups;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.GradesJsonConverter;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "groups")
@Getter
@Setter
public class Groups extends Submitter {
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
        name = "group_members", 
        joinColumns = @JoinColumn(name = "group_id"), 
        inverseJoinColumns = @JoinColumn(name = "person_id")
    )
    @JsonIgnore
    private List<Person> groupMembers = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
        name = "group_mentors",
        joinColumns = @JoinColumn(name = "group_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id")
    )
    @JsonIgnore
    private List<Person> groupMentors = new ArrayList<>();

    private String name;
    private String period;
    private String course;

    @Convert(converter = GradesJsonConverter.class)
    @Column(name = "gradesJson", columnDefinition = "text")
    private List<Map<String, Object>> gradesJson = new ArrayList<>();

    public Groups() {
    }

    public Groups(String name, String period, String course, List<Person> groupMembers) {
        this.name = name;
        this.period = period;
        this.course = course;
        this.groupMembers = groupMembers;
    }

    public void addPerson(Person person) {
        if (!this.groupMembers.contains(person)) {
            this.groupMembers.add(person);
            person.getGroups().add(this);
        }
    }

    public void removePerson(Person person) {
        if (this.groupMembers.contains(person)) {
            this.groupMembers.remove(person);
            person.getGroups().remove(this);
        }
    }

    public void addMentor(Person person) {
        if (!this.groupMentors.contains(person)) {
            this.groupMentors.add(person);
        }
    }

    public void removeMentor(Person person) {
        this.groupMentors.remove(person);
    }
}