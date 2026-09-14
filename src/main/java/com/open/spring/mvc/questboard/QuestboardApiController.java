package com.open.spring.mvc.questboard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin
@RequestMapping("/api/questboard")
public class QuestboardApiController {

    private final QuestboardJpaRepository repository;

    public QuestboardApiController(QuestboardJpaRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<Questboard>> getAllQuests() {
        return ResponseEntity.ok(repository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Questboard> getQuest(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Questboard> createQuest(@RequestBody Questboard quest) {
        Questboard savedQuest = repository.save(quest);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedQuest);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Questboard> updateQuest(
            @PathVariable Long id,
            @RequestBody Questboard quest) {

        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        quest.setId(id);
        Questboard updatedQuest = repository.save(quest);

        return ResponseEntity.ok(updatedQuest);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuest(@PathVariable Long id) {

        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}