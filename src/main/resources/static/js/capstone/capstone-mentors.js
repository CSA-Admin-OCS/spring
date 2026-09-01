// Mentor assignment for the capstone admin table.
//
// Same shape as group/group-mentors.js: the cell is filled client-side so a project
// row still renders if the endpoint is unavailable, rather than taking the page down.

async function fetchMentors(projectId) {
    const response = await fetch(`/api/capstones/${projectId}/mentors`, { method: "GET", cache: "no-cache" });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`mentors lookup failed: ${response.status}`);
    return response.json();
}

async function errorText(response, fallback) {
    try {
        const body = await response.text();
        return body && body.length < 300 ? body : `${fallback} (${response.status})`;
    } catch (e) {
        return `${fallback} (${response.status})`;
    }
}

async function mutateMentor(projectId, personId, method) {
    const response = await fetch(`/api/capstones/${projectId}/mentors/${personId}`, { method, cache: "no-cache" });
    if (!response.ok) throw new Error(await errorText(response, "Could not update mentor"));
}

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

function render(cell, projectId, mentors) {
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
        remove.title = `Remove ${mentor.uid} from this project`;
        remove.addEventListener("click", async () => {
            remove.disabled = true;
            try {
                await mutateMentor(projectId, mentor.id, "DELETE");
                await load(cell, projectId);
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
            await mutateMentor(projectId, await personIdForUid(uid.trim()), "POST");
            await load(cell, projectId);
        } catch (error) {
            window.alert(error.message);
        } finally {
            add.disabled = false;
        }
    });
    cell.appendChild(add);
}

async function load(cell, projectId) {
    cell.textContent = "…";
    try {
        const mentors = await fetchMentors(projectId);
        if (mentors === null) {
            cell.textContent = "unavailable";
            cell.className = "text-secondary";
            return;
        }
        cell.className = "";
        render(cell, projectId, mentors);
    } catch (error) {
        console.warn("Capstone mentors cell failed", error);
        cell.textContent = "unavailable";
        cell.className = "text-secondary";
    }
}

document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-capstone-mentor-cell]").forEach((cell) => {
        load(cell, cell.getAttribute("data-capstone-mentor-cell"));
    });

    const syncBtn = document.getElementById("capstone-sync");
    if (syncBtn) {
        syncBtn.addEventListener("click", async () => {
            const status = document.getElementById("capstone-sync-status");
            syncBtn.disabled = true;
            status.textContent = "syncing…";
            try {
                const response = await fetch("/api/capstones/sync", { method: "POST", cache: "no-cache" });
                const body = await response.json();
                if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
                status.textContent = `created ${body.created}, updated ${body.updated}, total ${body.total} — reloading…`;
                setTimeout(() => window.location.reload(), 900);
            } catch (error) {
                status.textContent = `sync failed: ${error.message}`;
                syncBtn.disabled = false;
            }
        });
    }
});
