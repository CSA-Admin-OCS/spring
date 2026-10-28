package com.open.spring.mvc.groups;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GroupChatIdentityService {

    private final PersonJpaRepository personRepository;

    public AuthenticatedIdentity getCurrentIdentity() {
        Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();

        if (auth == null
                || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken
                || auth.getName() == null
                || auth.getName().isBlank()) {
            throw new SecurityException("Authentication required");
        }

        String uid = auth.getName();
        Person person = personRepository.findByUid(uid);

        if (person == null
                || person.getName() == null
                || person.getName().isBlank()) {
            throw new SecurityException(
                    "Unable to resolve authenticated user");
        }

        return new AuthenticatedIdentity(uid, person.getName());
    }

    @Getter
    @RequiredArgsConstructor
    public static class AuthenticatedIdentity {
        private final String uid;
        private final String displayName;
    }
}
