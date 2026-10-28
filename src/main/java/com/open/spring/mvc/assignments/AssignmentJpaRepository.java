package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.open.spring.mvc.person.Person;

@Repository
public interface AssignmentJpaRepository extends JpaRepository<Assignment, Long> {
    Assignment findByName(String name);
    List<Assignment> findByAssignedGraders(Person grader);
    List<Assignment> findByAssignmentType(String assignmentType);  // NEW: Filter by assignment type
    // hello this is a test commit

    /**
     * The assignment auto-created for a lesson page, looked up on the indexed content_url
     * column instead of scanning every row for a description prefix.
     *
     * Ordered by id so deployments that accumulated duplicate rows before the column
     * existed keep resolving to the same (oldest) assignment every time.
     */
    Assignment findFirstByContentUrlOrderByIdAsc(String contentUrl);

    /**
     * Assignments owned by a creator. Matches on the stable Person id rather than the
     * entity itself because Person inherits identity equality from Submitter.
     */
    @Query("SELECT DISTINCT a FROM AssignmentEntity a JOIN a.creators c WHERE c.id = :personId")
    List<Assignment> findByCreatorId(@Param("personId") Long personId);

    /** Fetch-joins creators so DTO mapping does not trigger a lazy load per assignment. */
    @Query("SELECT DISTINCT a FROM AssignmentEntity a LEFT JOIN FETCH a.creators")
    List<Assignment> findAllWithCreators();

    /** Closes the assignment if it is open; returns 0 if it was already closed or doesn't exist. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentEntity a SET a.closedAt = :now, a.closedById = :closedById "
            + "WHERE a.id = :id AND a.closedAt IS NULL")
    int markClosed(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("closedById") Long closedById);

    /**
     * Locks the assignment row with a no-op UPDATE if it is open (SQLite has no SELECT ... FOR
     * UPDATE); returns 0 if it is closed or doesn't exist.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentEntity a SET a.closedAt = a.closedAt WHERE a.id = :id AND a.closedAt IS NULL")
    int lockIfOpen(@Param("id") Long id);
}
