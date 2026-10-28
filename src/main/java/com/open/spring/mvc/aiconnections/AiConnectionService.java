package com.open.spring.mvc.aiconnections;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import com.open.spring.mvc.person.PersonJpaRepository;

@Service
public class AiConnectionService {
    public record ProviderStatus(boolean connected, boolean modelConfigured, String updatedAt) {}
    public record Status(boolean available, Map<String, ProviderStatus> providers) {}
    private final AiConnectionRepository repository;
    private final AiKeyCipher cipher;
    private final AiProviderClient client;
    private final PersonJpaRepository people;

    public AiConnectionService(AiConnectionRepository repository, AiKeyCipher cipher,
            AiProviderClient client, PersonJpaRepository people) {
        this.repository = repository; this.cipher = cipher; this.client = client; this.people = people;
    }
    public Status status(Authentication authentication) {
        long id = personId(authentication);
        boolean available = cipher.isAvailable() && repository.isAvailable();
        Map<String, ProviderStatus> providers = new LinkedHashMap<>();
        for (AiProvider provider : AiProvider.values()) {
            // Connection metadata and removal remain usable if only the encryption key is missing.
            Optional<AiConnectionRepository.StoredKey> stored = repository.isAvailable()
                ? repository.find(id, provider) : Optional.empty();
            providers.put(provider.name(), new ProviderStatus(stored.isPresent(), client.isConfigured(provider),
                stored.map(AiConnectionRepository.StoredKey::updatedAt).orElse(null)));
        }
        return new Status(available, providers);
    }
    public Status connect(Authentication authentication, AiProvider provider, String key) {
        long id = personId(authentication);
        cipher.requireAvailable();
        if (!repository.isAvailable()) throw AiConnectionException.unavailable();
        if (key == null || key.isBlank() || key.length() > 512 || !key.equals(key.trim())
                || key.chars().anyMatch(Character::isWhitespace)) {
            throw new AiConnectionException(400, "AI_KEY_INVALID", "Enter a non-empty API key without whitespace (maximum 512 characters).");
        }
        client.validate(provider, key);
        repository.save(id, provider, cipher.encrypt(id, provider, key));
        return status(authentication);
    }
    public void disconnect(Authentication authentication, AiProvider provider) {
        repository.delete(personId(authentication), provider);
    }
    /** Called only with the authorized submission's persisted owner, never a profile request UID. */
    public Optional<String> credential(long personId, AiProvider provider) {
        if (!cipher.isAvailable() || !repository.isAvailable()) return Optional.empty();
        try {
            return repository.find(personId, provider).map(row -> cipher.decrypt(personId, provider, row.ciphertext()));
        } catch (AiConnectionException e) {
            LoggerFactory.getLogger(getClass()).warn("Personal AI credential unavailable for provider {}; continuing to fallback.", provider);
            return Optional.empty();
        }
    }
    private long personId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)
            throw new AiConnectionException(401, "UNAUTHENTICATED", "Sign in to Spring to manage AI connections.");
        var person = people.findByUid(authentication.getName());
        if (person == null) throw new AiConnectionException(401, "UNAUTHENTICATED", "Spring account could not be found.");
        return person.getId();
    }
}
