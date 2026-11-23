package com.open.spring.security.rbac;

import java.util.List;

/**
 * Routes labelled by a person reading the rule lists in SecurityConfig and MvcSecurityConfig, not by running anything.
 *
 * <p>A tool checked only against itself proves nothing. These are the answers worked out by hand, from the first rule
 * that matches the path and method (the rules are first-match-wins), and the probe's reading has to agree with every
 * one. They are drawn from each kind of rule: open, any signed-in account, a list of roles, the default rule, a rule
 * that is narrower or wider than the one after it, a path that looks open and is not, and the MVC chain.
 *
 * <p>Change a label only when the rule it was read from really changes.
 */
final class SampleLabels {

    record Label(String request, String who, String because) {
    }

    private static final String ALL4 = "roles:ROLE_ADMIN,ROLE_STUDENT,ROLE_TEACHER,ROLE_USER";
    private static final String STUDENT_TEACHER_ADMIN = "roles:ROLE_ADMIN,ROLE_STUDENT,ROLE_TEACHER";
    private static final String TEACHER_ADMIN = "roles:ROLE_ADMIN,ROLE_TEACHER";
    private static final String ADMIN = "roles:ROLE_ADMIN";
    private static final String ALL4_AND_SYNC = "roles:ROLE_ADMIN,ROLE_ASSIGNMENT_SYNC,ROLE_STUDENT,ROLE_TEACHER,ROLE_USER";
    private static final String MENTOR = "roles:ROLE_MENTOR";

    static final List<Label> ALL = List.of(
            // ---- the API chain: open
            new Label("POST /authenticate", "anyone", "permitAll"),
            new Label("POST /api/person/create", "anyone", "permitAll for POST on this path"),
            new Label("GET /api/person/uid/{uid}", "anyone", "permitAll for GET /api/person/uid/**"),
            new Label("POST /api/pausemenu/score", "anyone", "/api/pausemenu/** is permitAll; only DELETE is Admin"),
            new Label("GET /api/pausemenu/score/all", "anyone", "/api/pausemenu/** is permitAll"),
            new Label("GET /api/leaderboard", "anyone", "/api/leaderboard/** is permitAll"),
            new Label("GET /api/grades/read-gist/{id}", "anyone", "GET read-gist is permitAll, though POST create-gist is not"),
            new Label("POST /api/face/register/public", "anyone", "permitAll, listed before /api/face/**"),
            new Label("POST /api/assignment-submissions/upload", "anyone", "permitAll"),
            new Label("POST /api/cs-pathway/setup-report/{pairingCode}", "anyone", "setup-report/* is permitAll: one segment"),
            new Label("POST /api/toolchain/pair", "anyone", "permitAll"),
            new Label("GET /api/toolchain/sessions/{sessionId}/next", "anyone", "permitAll for sessions/*/next"),
            new Label("GET /api/ui-builder/published", "anyone", "permitAll"),
            new Label("GET /api/ui-builder/published/{slug}", "anyone", "published/* is permitAll: one segment"),

            // ---- the API chain: any signed-in account
            new Label("POST /api/submissions/ai-grade/{submissionId}", "signed_in", "POST /api/submissions/** is authenticated"),
            new Label("GET /api/ai-connections", "signed_in", "/api/ai-connections is authenticated"),
            new Label("GET /api/gist-connection", "signed_in", "authenticated"),
            new Label("POST /api/grades/create-gist", "signed_in", "authenticated"),

            // ---- the API chain: a list of roles
            new Label("DELETE /api/person/{id}", ADMIN, "DELETE /api/person/** is Admin"),
            new Label("PUT /api/person/{id}", ADMIN, "PUT /api/person/** is Admin"),
            new Label("GET /api/exports/getAll", ADMIN, "/api/exports/** is Admin"),
            new Label("GET /api/imports/logs", ADMIN, "/api/imports/** is Admin"),
            new Label("DELETE /api/content/{id}", STUDENT_TEACHER_ADMIN, "/api/content/** is Student, Teacher, Admin: no User"),
            new Label("GET /api/events/{param}", STUDENT_TEACHER_ADMIN, "/api/events/** is Student, Teacher, Admin: no User"),
            new Label("POST /api/synergy/grade", TEACHER_ADMIN, "POST /api/synergy/** is Teacher, Admin"),
            new Label("POST /api/synergy/grades/requests", STUDENT_TEACHER_ADMIN, "the one POST under synergy a Student may make, listed first"),
            new Label("POST /api/quests/create", TEACHER_ADMIN, "POST /api/quests/** is Teacher, Admin"),
            new Label("DELETE /api/certificates/{id}", TEACHER_ADMIN, "DELETE /api/certificates/** is Teacher, Admin"),
            new Label("GET /api/face/faces", TEACHER_ADMIN, "GET faces is Teacher, Admin"),
            new Label("POST /api/face/register", ALL4, "any role but Mentor and the sync account"),
            new Label("POST /api/cs-pathway/setup-report/pairing-code", ALL4, "listed before setup-report/*, so it is not open"),
            new Label("POST /api/cs-pathway/setup-report/review/{uid}", TEACHER_ADMIN, "review is Teacher, Admin"),
            new Label("POST /api/assignments/auto-create", ALL4_AND_SYNC, "the one route the Pages bot's role is added to"),
            new Label("POST /api/upai", ALL4, "listed"),
            new Label("GET /api/jokes/", ALL4, "/api/jokes/** is four roles"),
            new Label("GET /api/scraper/results", ALL4, "/api/scraper/** is four roles"),
            new Label("GET /api/analytics/", ALL4, "/api/analytics/** is four roles"),
            new Label("GET /api/calendar/events", ALL4, "/api/calendar/** is four roles"),
            new Label("GET /api/chat/{groupId}", ALL4, "/api/chat/** is four roles"),
            new Label("GET /api/files/download", ALL4, "/api/files/** is four roles"),
            new Label("GET /api/ocs-analytics/user", ALL4, "/api/ocs-analytics/** is four roles"),

            // ---- the API chain: the default rule, for requests no earlier rule names
            new Label("GET /api/person/{id}", ALL4, "only DELETE and PUT are Admin: GET falls to the default"),
            new Label("GET /api/person/get", ALL4, "default"),
            new Label("POST /api/person/update", ALL4, "only PUT is Admin: POST falls to the default"),
            new Label("GET /api/quests/{id}", ALL4, "only POST, PUT, DELETE under quests are narrowed: GET is the default"),
            new Label("GET /api/certificates", ALL4, "only POST, PUT, DELETE are narrowed: GET is the default"),
            new Label("GET /api/synergy/grades", ALL4, "only POST under synergy is narrowed: GET is the default"),
            new Label("GET /api/submissions/ai-queue", ALL4, "only POST under submissions is authenticated: GET is the default"),
            new Label("POST /api/toolchain/sessions", ALL4, "only pair, next and results are open"),
            new Label("GET /api/ui-builder/pages", ALL4, "only published and published/* are open"),
            new Label("GET /api/ui-builder/published/{slug}/members/{blockId}", ALL4, "published/* is one segment: this has more"),

            // ---- the MVC chain
            new Label("GET /mvc/person/create", "anyone", "permitAll"),
            new Label("POST /mvc/person/reset/start", "anyone", "permitAll"),
            new Label("POST /mvc/person/reset/check", "anyone", "permitAll"),
            new Label("POST /mvc/person/reset/admin/{id}", "signed_in", "looks like the open reset paths and is not listed: the default"),
            new Label("POST /run/java", "anyone", "/run/** is permitAll"),
            new Label("GET /mvc/person/read", "signed_in", "/mvc/person/read/** is authenticated"),
            new Label("GET /mvc/person/update/user", "signed_in", "listed before update/**, so a signed-in account may"),
            new Label("GET /mvc/person/update/{id}", ADMIN, "GET update/** is Admin"),
            new Label("POST /mvc/person/update", "signed_in", "authenticated"),
            new Label("POST /mvc/person/update/role", ADMIN, "Admin"),
            new Label("GET /mvc/person/delete/{id}", ADMIN, "delete/** is Admin"),
            new Label("GET /mvc/synergy/view-grade-requests", TEACHER_ADMIN, "listed before synergy/**"),
            new Label("GET /mvc/synergy/gradebook", "signed_in", "/mvc/synergy/** is authenticated"),
            new Label("GET /mvc/assignments/tracker", TEACHER_ADMIN, "Teacher, Admin"),
            new Label("GET /mvc/assignments", "signed_in", "no rule: the default is authenticated"),
            new Label("GET /mvc/extract/all/person", ADMIN, "extract/** is Admin"),
            new Label("POST /mvc/import/person", ADMIN, "POST import/** is Admin"),
            new Label("GET /mvc/bank/read", ADMIN, "Admin"),
            new Label("GET /mvc/progress/read", TEACHER_ADMIN, "Teacher, Admin"),
            new Label("GET /mvc/cs-pathway/read", TEACHER_ADMIN, "cs-pathway/** is Teacher, Admin"),
            new Label("GET /mvc/bathroom/hallpass", "signed_in", "bathroom/** is authenticated"),
            new Label("POST /mvc/person/capstones/{personId}/approve", TEACHER_ADMIN, "POST capstones/** is Teacher, Admin"),

            // ---- rules added with the capstone mentoring and the video calls
            new Label("GET /api/person/capstones/mentors", "anyone", "the public read, listed before the Mentor-only rule: names only"),
            new Label("GET /api/person/capstones", MENTOR, "Mentor only: not Admin, not Teacher"),
            new Label("PUT /api/person/capstones/choice", MENTOR, "listed before PUT /api/person/**, which is Admin"),
            new Label("DELETE /api/person/capstones/choices", MENTOR, "listed before DELETE /api/person/**, which is Admin"),
            new Label("POST /api/calls/webhook", "anyone", "permitAll: the call server has no login cookie and the handler checks its signature"),
            new Label("POST /api/calls", ALL4, "no rule names it: the default, which keeps Mentors out"),
            new Label("GET /api/calls/live", ALL4, "default"));

    private SampleLabels() {
    }
}
