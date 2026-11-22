package com.open.spring.mvc.calls;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.person.Person;

/** GET /api/calls/live: the calls happening now that the caller may join, for course pages. */
class CallLiveHttpTest {

    private static final String ORIGIN = "http://localhost:4500";

    private CallTestStore store;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken ada;
    private UsernamePasswordAuthenticationToken teacher;
    private CallRoomEntity group12;
    private CallRoomEntity group13;
    private CallRoomEntity csa;

    @BeforeEach
    void setUp() {
        store = new CallTestStore();
        Person adaPerson = store.person(1, "ada", "ROLE_USER", "ROLE_STUDENT");
        Person nikoPerson = store.person(2, "niko", "ROLE_USER", "ROLE_STUDENT");
        Person teacherPerson = store.person(4, "mort", "ROLE_USER", "ROLE_TEACHER");
        ada = CallTestStore.login(adaPerson);
        teacher = CallTestStore.login(teacherPerson);
        Groups binaryBeasts = store.group(12, "Binary Beasts", adaPerson);
        binaryBeasts.setCourse("csa");
        store.group(13, "Null Pointers", nikoPerson).setCourse("csa");
        store.group(70, "CSA", adaPerson, nikoPerson);
        group12 = live(store.service.openFor(adaPerson, CallContextType.GROUP, "12", null), "id-ada");
        group13 = live(store.service.openFor(nikoPerson, CallContextType.GROUP, "13", null), "id-niko");
        csa = store.service.openFor(adaPerson, CallContextType.COURSE, "csa", null);
        mvc = MockMvcBuilders.standaloneSetup(store.controller())
                .setControllerAdvice(new CallExceptionHandler(store.properties))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    private CallRoomEntity live(CallRoomEntity room, String identity) {
        room.setState(CallState.LIVE);
        store.presence.joined(room.getRoomName(), "PA_" + identity, new CallParticipant(identity, null, Set.of("ROLE_STUDENT")));
        return room;
    }

    private ResultActions liveCalls(UsernamePasswordAuthenticationToken who, String query) throws Exception {
        return mvc.perform(get("/api/calls/live" + query).principal(who).header("Origin", ORIGIN).header("X-Origin", "client"));
    }

    @Test
    void listsOnlyJoinableLiveRooms() throws Exception {
        liveCalls(ada, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(group12.getId().intValue()))
                .andExpect(jsonPath("$[0].title").value("Binary Beasts"))
                .andExpect(jsonPath("$[0].contextType").value("GROUP"))
                .andExpect(jsonPath("$[0].count").value(1));
    }

    @Test
    void aTeacherSeesEveryLiveCallAndWhichAreOneOnOne() throws Exception {
        store.presence.joined(group12.getRoomName(), "PA_t", new CallParticipant("id-t", null, Set.of("ROLE_TEACHER")));

        liveCalls(teacher, "")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.title == 'Binary Beasts')].oneOnOne").value(true))
                .andExpect(jsonPath("$[?(@.title == 'Null Pointers')].oneOnOne").value(false));
    }

    @Test
    void emptyRoomsAreNotLive() throws Exception {
        store.presence.left(group12.getRoomName(), "PA_id-ada");

        liveCalls(ada, "").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aCourseListsItsOwnRoomAndItsTeamsRooms() throws Exception {
        live(csa, "id-ada");
        store.group(14, "Other Course Team", store.person(5, "linus", "ROLE_USER", "ROLE_STUDENT")).setCourse("csp");

        liveCalls(teacher, "?course=csa")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.contextType == 'COURSE')].title").value("CSA"));
        liveCalls(teacher, "?course=csp").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theListIsGuardedLikeEveryRoute() throws Exception {
        mvc.perform(get("/api/calls/live").principal(ada))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("origin_rejected"));
    }
}
