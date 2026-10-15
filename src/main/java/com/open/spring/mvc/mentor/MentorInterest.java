package com.open.spring.mvc.mentor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A capstone project a mentor marked "Interested" in on the Mentor Portal
 * discovery deck (/projects). This is just the mentor's own shortlist --
 * separate from an actual mentorship assignment, and separate from applying
 * (the "Apply" action is still a client-side placeholder, see projects.md).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MentorInterest {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "person_id", nullable = false)
    @JsonIgnore
    private Person person;

    private String projectUrl;

    private String projectTitle;

    private Long createdAt;
}
