package com.open.spring.mvc.mentor;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.Getter;
import lombok.Setter;

/**
 * A mentor's "Interested" shortlist from the Mentor Portal swipe deck
 * (/projects), persisted per-account so it follows them across browsers and
 * devices. Falls under the default /api/** authorization rule in
 * SecurityConfig (any of ROLE_USER/ROLE_ADMIN/ROLE_TEACHER/ROLE_STUDENT) --
 * no special role required beyond being signed in.
 */
@RestController
@RequestMapping("/api/mentor/interests")
public class MentorInterestApiController {

    @Autowired
    private MentorInterestJpaRepository interestRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Getter
    @Setter
    public static class InterestDto {
        private String url;
        private String title;
    }

    private Person currentPerson(UserDetails userDetails) {
        if (userDetails == null) {
            return null;
        }
        return personRepository.findByUid(userDetails.getUsername());
    }

    @GetMapping
    public ResponseEntity<List<MentorInterest>> list(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        return new ResponseEntity<>(interestRepository.findByPerson_IdOrderByCreatedAtAsc(person.getId()), HttpStatus.OK);
    }

    @PostMapping
    public ResponseEntity<List<MentorInterest>> add(@AuthenticationPrincipal UserDetails userDetails, @RequestBody InterestDto body) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (body == null || body.getUrl() == null || body.getUrl().isBlank()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        boolean exists = interestRepository.findByPerson_IdAndProjectUrl(person.getId(), body.getUrl()).isPresent();
        if (!exists) {
            MentorInterest interest = new MentorInterest();
            interest.setPerson(person);
            interest.setProjectUrl(body.getUrl());
            interest.setProjectTitle(body.getTitle());
            interest.setCreatedAt(System.currentTimeMillis());
            interestRepository.save(interest);
        }

        return new ResponseEntity<>(interestRepository.findByPerson_IdOrderByCreatedAtAsc(person.getId()), HttpStatus.OK);
    }
}
