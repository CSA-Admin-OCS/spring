// Mentor assignment for the groups admin table.
//
// A capstone project is a Groups row; an industry mentor is attached to a few of
// them and sees only those. This fills the Mentors cell for each row and gives an
// admin add/remove controls.
//
// Deliberately client-side rather than a Thymeleaf ${group.groupMentors} expression:
// the mentor relation lands separately, and a template expression for a field that
// does not exist yet would take the whole /mvc/groups/read page down. Here a missing
// endpoint degrades to "unavailable" on one cell.

const MENTORS_UNAVAILABLE = "unavailable";

async function fetchMentors(groupId) {
    const response = await fetch(`/api/groups/${groupId}/mentors`, {
        method: "GET",
        cache: "no-cache",
    });
    if (response.status === 404) return null; // endpoint not deployed yet
    if (!response.ok) throw new Error(`mentors lookup failed: ${response.status}`);
    return response.json();
}

async function addMentor(groupId, personId) {
    const response = await fetch(`/api/groups/${groupId}/mentors/${personId}`, {
        method: "POST",
        cache: "no-cache",
    });
    if (!response.ok) throw new Error(await errorText(response, "Could not add mentor"));
}

async function removeMentor(groupId, personId) {
    const response = await fetch(`/api/groups/${groupId}/mentors/${personId}`, {
        method: "DELETE",
        cache: "no-cache",
    });
    if (!response.ok) throw new Error(await errorText(response, "Could not remove mentor"));
}

async function errorText(response, fallback) {
    try {
        const body = await response.text();
        return body && body.length < 300 ? body : `${fallback} (${response.status})`;
    } catch (e) {
        return `${fallback} (${response.status})`;
    }
}

// Resolve a typed uid to a person id. /api/people is what the groups admin UI in the
// pages repo already uses for the same purpose.
let peopleCache = null;
async function personIdForUid(uid) {
    if (peopleCache === null) {
        const response = await fetch("/api/people", { method: "GET", cache: "no-cache" });
        if (!response.ok) throw new Error("Could not load people");
        peopleCache = await response.json();
    }
    const match = peopleCache.find((person) => person.uid === uid);
    if (!match) throw new Error(`No account with uid "${uid}"`);
    return match.id;
}

function renderMentors(cell, groupId, mentors) {
    cell.textContent = "";

    const list = document.createElement("ul");
    list.className = "list-unstyled mb-0";

    if (mentors.length === 0) {
        const empty = document.createElement("li");
        empty.className = "text-secondary";
        empty.textContent = "none";
        list.appendChild(empty);
    }

    mentors.forEach((mentor) => {
        const item = document.createElement("li");
        item.textContent = `${mentor.name} (${mentor.uid}) `;

        const remove = document.createElement("button");
        remove.className = "btn btn-outline-danger btn-sm";
        remove.type = "button";
        remove.textContent = "x";
        remove.title = `Remove ${mentor.uid} as a mentor`;
        remove.addEventListener("click", async () => {
            remove.disabled = true;
            try {
                await removeMentor(groupId, mentor.id);
                await loadCell(cell, groupId);
            } catch (error) {
                window.alert(error.message);
                remove.disabled = false;
            }
        });

        item.appendChild(remove);
        list.appendChild(item);
    });

    cell.appendChild(list);

    const add = document.createElement("button");
    add.className = "btn btn-outline-secondary btn-sm mt-1";
    add.type = "button";
    add.textContent = "+ mentor";
    add.addEventListener("click", async () => {
        const uid = window.prompt("GitHub ID (uid) of the mentor to attach:");
        if (!uid) return;
        add.disabled = true;
        try {
            const personId = await personIdForUid(uid.trim());
            await addMentor(groupId, personId);
            await loadCell(cell, groupId);
        } catch (error) {
            window.alert(error.message);
        } finally {
            add.disabled = false;
        }
    });
    cell.appendChild(add);
}

async function loadCell(cell, groupId) {
    cell.textContent = "…";
    try {
        const mentors = await fetchMentors(groupId);
        if (mentors === null) {
            cell.textContent = MENTORS_UNAVAILABLE;
            cell.className = "text-secondary";
            return;
        }
        cell.className = "";
        renderMentors(cell, groupId, mentors);
    } catch (error) {
        console.warn("Mentors cell failed", error);
        cell.textContent = MENTORS_UNAVAILABLE;
        cell.className = "text-secondary";
    }
}

document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-mentor-cell]").forEach((cell) => {
        loadCell(cell, cell.getAttribute("data-mentor-cell"));
    });
});
