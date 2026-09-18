package com.open.spring.mvc.directory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DirectoryAccountControllerTest {
    private DirectoryAccountRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        repository = mock(DirectoryAccountRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new DirectoryAccountController(repository)).build();
    }

    @Test
    void createsWithServerManagedIdentity() throws Exception {
        when(repository.save(any())).thenAnswer(invocation -> {
            DirectoryAccount account = invocation.getArgument(0);
            assertNull(account.getId());
            assertNull(account.getCreatedAt());
            assertEquals("001234", account.getStudentID());
            account.setId(12L);
            return account;
        });
        mvc.perform(post("/mvc/data/directory").param("name", "Student")
                .param("email", "student@example.com").param("studentID", "001234")
                .param("accountType", "STUDENT").param("id", "99")
                .param("createdAt", "2000-01-01T00:00:00"))
            .andExpect(redirectedUrl("/mvc/data/directory/12"));
    }

    @Test
    void invalidFieldsNeverSave() throws Exception {
        mvc.perform(post("/mvc/data/directory").param("name", "")
                .param("email", "invalid").param("accountType", "ADMIN"))
            .andExpect(view().name("directory/form"))
            .andExpect(model().attributeHasFieldErrors("account", "name", "email", "accountType"));
        verifyNoInteractions(repository);
    }

    @Test
    void editPreservesIdentityAndCreationTime() throws Exception {
        DirectoryAccount existing = new DirectoryAccount();
        existing.setId(7L);
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 0, 0);
        existing.setCreatedAt(created);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        mvc.perform(post("/mvc/data/directory/7").param("name", "Guest")
                .param("email", "guest@example.com").param("accountType", "GUEST")
                .param("school", "New school").param("id", "99"))
            .andExpect(redirectedUrl("/mvc/data/directory/7"));
        assertEquals(7L, existing.getId());
        assertEquals(created, existing.getCreatedAt());
        assertEquals("New school", existing.getSchool());
        verify(repository).save(existing);
    }

    @Test
    void missingRecordReturns404() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(get("/mvc/data/directory/99")).andExpect(status().isNotFound());
        mvc.perform(post("/mvc/data/directory/99/delete")).andExpect(status().isNotFound());
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteRemovesSelectedRecord() throws Exception {
        DirectoryAccount account = new DirectoryAccount();
        when(repository.findById(7L)).thenReturn(Optional.of(account));
        mvc.perform(post("/mvc/data/directory/7/delete"))
            .andExpect(redirectedUrl("/mvc/data/directory"));
        verify(repository).delete(account);
    }
}
