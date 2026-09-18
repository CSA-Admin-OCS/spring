// Run against a disposable local database only. Requires Node 18+.
// DIRECTORY_BASE_URL, DIRECTORY_ADMIN_UID, DIRECTORY_ADMIN_PASSWORD are required.
import assert from 'node:assert/strict';

const base = process.env.DIRECTORY_BASE_URL;
const username = process.env.DIRECTORY_ADMIN_UID;
const password = process.env.DIRECTORY_ADMIN_PASSWORD;
assert(base && username && password, 'Set DIRECTORY_BASE_URL, DIRECTORY_ADMIN_UID and DIRECTORY_ADMIN_PASSWORD');
assert(['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use a local disposable server');
const root = '/mvc/data/directory';
const cookies = new Map();
async function request(path, { method = 'GET', form, headers = {}, session = true } = {}) {
    const response = await fetch(new URL(path, base), {
        method, redirect: 'manual',
        headers: { ...(session ? { Cookie: [...cookies].map(([k,v]) => `${k}=${v}`).join('; ') } : {}), ...headers },
        body: form ? new URLSearchParams(form) : undefined,
    });
    if (session) for (const cookie of response.headers.getSetCookie()) {
        const [pair] = cookie.split(';');
        const at = pair.indexOf('=');
        // Deliberately retain only the MVC session, never the API JWT.
        if (!pair.startsWith('jwt_java_spring=')) cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
    return { status: response.status, headers: response.headers, html: await response.text() };
}
function csrf(html) {
    const input = [...html.matchAll(/<input\b[^>]*>/g)].map(m => m[0]).find(s => /name="_csrf"/.test(s));
    assert(input, 'Rendered HTML must contain CSRF input');
    return input.match(/value="([^"]+)"/)[1];
}
function passed(label) { console.log(`PASS ${label}`); }

assert.equal((await request(root, {session:false})).status, 302);
const login = await request('/login');
assert.equal(login.status, 200);
assert.equal((await request('/login', {method:'POST', form:{username,password}})).status, 403);
const signedIn = await request('/login', {method:'POST', form:{username,password,_csrf:csrf(login.html)}});
assert.equal(signedIn.status, 302);
assert.equal(new URL(signedIn.headers.get('location'), base).pathname, '/mvc/person/read');
assert(cookies.size > 0);
passed('real form login with session cookie and CSRF');

const list = await request(root);
assert.equal(list.status, 200);
assert.match(list.headers.get('content-type'), /text\/html/);
assert.match(list.html, /Account directory/);
const form = await request(`${root}/new`);
assert.equal(form.status, 200);
assert.match(form.html, /action="\/mvc\/data\/directory"/);
const token = csrf(form.html);
const fields = {name:'<script>directory-test</script>', email:'directory-test@example.com', school:'Test school', studentID:'001234', githubUsername:'test-user', accountType:'STUDENT'};
for (const path of [root, `${root}/999999`, `${root}/999999/delete`]) {
    assert.equal((await request(path, {method:'POST', form:fields})).status, 403);
    assert.equal((await request(path, {method:'POST', form:{...fields,_csrf:'invalid'}})).status, 403);
}
for (const method of ['GET','POST','OPTIONS']) {
    const response = await request(root, {method, headers:{Origin:'http://localhost:4500', ...(method==='OPTIONS'?{'Access-Control-Request-Method':'POST'}:{})}});
    assert.equal(response.status, 403);
    assert.equal(response.headers.get('access-control-allow-origin'), null);
}
assert.equal((await request(root, {headers:{Origin:base}})).status, 200);
passed('rendered HTML, CSRF rejection, cross-origin rejection, same-origin access');

const invalid = await request(root, {method:'POST', form:{...fields,name:'',email:'bad',accountType:'ADMIN',_csrf:token}});
assert.equal(invalid.status, 200);
assert.match(invalid.html, /Please correct these fields/);
const created = await request(root, {method:'POST', form:{...fields,id:'999999',createdAt:'2000-01-01T00:00:00',_csrf:csrf(invalid.html)}});
assert.equal(created.status, 302);
const detailPath = new URL(created.headers.get('location'), base).pathname;
assert.match(detailPath, /^\/mvc\/data\/directory\/\d+$/);
assert(!detailPath.endsWith('/999999'));
try {
    const detail = await request(detailPath);
    assert.equal(detail.status, 200);
    assert.match(detail.html, /&lt;script&gt;directory-test&lt;\/script&gt;/);
    assert.match(detail.html, /001234/);
    const timestamp = detail.html.match(/<dt>Joined \(UTC\)<\/dt><dd>([^<]+)<\/dd>/)[1];
    assert(!timestamp.startsWith('2000-'));
    const edit = await request(`${detailPath}/edit`);
    assert.equal(edit.status, 200);
    assert.match(edit.html, /value="001234"/);
    const updated = await request(detailPath, {method:'POST', form:{...fields,school:'Updated school',_csrf:csrf(edit.html)}});
    assert.equal(updated.status, 302);
    const after = await request(detailPath);
    assert.match(after.html, /Updated school/);
    assert(after.html.includes(timestamp));
    passed('SQLite-backed create/read/update, escaped output, leading zeros, immutable identity/time');
} finally {
    const page = await request(detailPath);
    const deleted = await request(`${detailPath}/delete`, {method:'POST', form:{_csrf:csrf(page.html)}});
    assert.equal(deleted.status, 302);
    assert.equal((await request(detailPath)).status, 404);
}
const guestForm = await request(`${root}/new`);
const guest = await request(root, {method:'POST', form:{name:'Verification Guest',email:'guest@example.com',accountType:'GUEST',_csrf:csrf(guestForm.html)}});
assert.equal(guest.status,302);
const guestPath = new URL(guest.headers.get('location'), base).pathname;
const guestPage = await request(guestPath);
assert.equal(guestPage.status,200);
assert.match(guestPage.html, /GUEST/);
assert.equal((await request(`${guestPath}/delete`, {method:'POST',form:{_csrf:csrf(guestPage.html)}})).status,302);
passed('delete, missing-record 404, guest with optional fields blank');

assert.equal((await request('/api/jokes/', {session:false})).status,401);
// API authentication consumes JSON, not a form.
const jwt = await fetch(new URL('/authenticate',base), {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({uid:username,password})});
assert.equal(jwt.status,200);
const jwtCookie = jwt.headers.getSetCookie().find(c => c.startsWith('jwt_java_spring='));
assert(jwtCookie);
assert.equal((await request('/api/jokes/', {session:false,headers:{Cookie:jwtCookie.split(';')[0]}})).status,200);
assert.equal((await request(root, {session:false,headers:{Cookie:jwtCookie.split(';')[0]}})).status,302);
passed('API jokes and JWT authentication still use the API chain');

const logoutForm = await request(`${root}/new`);
assert.equal((await request('/logout',{method:'POST',form:{_csrf:csrf(logoutForm.html)}})).status,302);
assert.equal((await request(root)).status,302);
passed('CSRF-protected logout invalidates the session');
