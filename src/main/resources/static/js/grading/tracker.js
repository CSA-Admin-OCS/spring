// Assignment tracker: searchable/filterable assignment list, an assignment window with tabs,
// and test tools. Data comes from /api/assignments/tracker; helpers from grading/common.js.
(function () {
  const { esc, api, postJson, formatEta, formatGrade, debounce, toast, contentText, submissionDetailHtml,
    gradingOutcomeText, saveManualGrade, clearManualGrade, requestAiGrade, gradeSourceBadge, closeAssignment,
    reviews, REVIEW_REASONS, REVIEW_DECISIONS, SIMILARITY_BASIS } = window.Grading;
  const SUBMISSION_TYPES = ['text', 'link', 'github_issue', 'code', 'file'];
  const $ = id => document.getElementById(id);

  let assignments = [];
  let current = null;          // assignment shown in the window
  let submissions = [];
  let queueEta = {};           // submissionId -> eta seconds
  let graders = new Map();     // id -> {id, name, uid} for the graders panel

  // ---------- dates: the API stores MM/DD/YYYY, date inputs use YYYY-MM-DD ----------
  function toIsoDate(mmddyyyy) {
    const m = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec((mmddyyyy || '').trim());
    return m ? `${m[3]}-${m[1].padStart(2, '0')}-${m[2].padStart(2, '0')}` : '';
  }
  function fromIsoDate(iso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
    return m ? `${m[2]}/${m[3]}/${m[1]}` : '';
  }

  // ---------- list ----------
  async function loadAssignments() {
    try {
      assignments = await api('/api/assignments/tracker');
    } catch (e) {
      $('assignmentRows').innerHTML = `<tr><td colspan="6" class="g-empty">Could not load assignments: ${esc(e.message)}</td></tr>`;
      return;
    }
    assignments.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    buildTypeFilters();
    renderList();
    fillCheckSelect();
  }

  function buildTypeFilters() {
    const checked = new Set([...document.querySelectorAll('#typeFilters input:checked')].map(i => i.value));
    const types = [...new Set(assignments.map(a => (a.assignmentType || '(none)').toLowerCase()))].sort();
    $('typeFilters').innerHTML = types.map(t => `
      <label><input type="checkbox" value="${esc(t)}" ${checked.has(t) ? 'checked' : ''}> ${esc(t)}
        <span class="g-sub mb-0">(${assignments.filter(a => (a.assignmentType || '(none)').toLowerCase() === t).length})</span></label>`).join('');
  }

  function activeFilters() {
    return {
      types: [...document.querySelectorAll('#typeFilters input:checked')].map(i => i.value),
      due: fromIsoDate($('dueFilter').value),
      rubric: $('rubricFilter').checked,
      min: $('pointsMin').value === '' ? null : Number($('pointsMin').value),
      max: $('pointsMax').value === '' ? null : Number($('pointsMax').value),
    };
  }

  function renderList() {
    const needle = $('assignmentSearch').value.trim().toLowerCase();
    const f = activeFilters();
    const count = f.types.length + (f.due ? 1 : 0) + (f.rubric ? 1 : 0) + (f.min != null || f.max != null ? 1 : 0);
    $('filterCount').textContent = count ? `(${count})` : '';
    const rows = assignments.filter(a =>
      (!needle || (a.name || '').toLowerCase().includes(needle))
      && (!f.types.length || f.types.includes((a.assignmentType || '(none)').toLowerCase()))
      && (!f.due || toIsoDate(a.dueDate) === toIsoDate(f.due))
      && (!f.rubric || !a.rubricReady)
      && (f.min == null || (a.points ?? 0) >= f.min)
      && (f.max == null || (a.points ?? 0) <= f.max));
    $('assignmentCount').textContent = `${rows.length} of ${assignments.length}`;
    $('assignmentRows').innerHTML = rows.length ? rows.map(a => `
      <tr class="g-click" data-id="${a.id}" tabindex="0">
        <td>${esc(a.name)}</td>
        <td>${esc(a.assignmentType || '—')}</td>
        <td class="text-nowrap">${esc(a.dueDate || '—')}</td>
        <td>${a.points ?? '—'}</td>
        <td class="text-nowrap">${a.submissionCount}${a.ungradedCount ? ` <span class="g-sub">(${a.ungradedCount} ungraded)</span>` : ''}</td>
        <td>${a.rubricReady ? '<span class="g-badge ok">AI</span>' : '<span class="g-badge warn">Default</span>'}</td>
      </tr>`).join('')
      : '<tr><td colspan="6" class="g-empty">No assignments match.</td></tr>';
  }

  // ---------- assignment window ----------
  function openModal(id) { $(id).classList.add('open'); }
  function closeModal(el) { el.classList.remove('open'); }

  function openAssignment(id) {
    current = assignments.find(a => a.id === id);
    if (!current) return;
    renderHeader();
    showTab('submissions');
    openModal('assignmentModal');
  }

  function renderHeader() {
    const a = current;
    $('amTitle').textContent = a.name;
    $('amSubtitle').textContent = `#${a.id}${a.contentUrl ? ' · ' + a.contentUrl : ''}`
      + (a.closedAt ? ` · Closed ${new Date(a.closedAt).toLocaleString()}: grades are final` : ' · Open');
    $('closeAssignment').disabled = !!a.closedAt;
    $('closeStatus').textContent = a.closedAt
      ? 'Closed. Grade changes now go through a teacher review on each submission.'
      : 'Closing makes every grade final and copies it to the gradebook. It cannot be undone.';
    $('amStats').innerHTML = [
      [a.submissionCount, 'submissions'],
      [a.ungradedCount, 'ungraded'],
      [a.pendingAiCount, 'in AI queue'],
      [a.averageGrade == null ? '—' : formatGrade(a.averageGrade, a.points), 'average grade'],
    ].map(([v, label]) => `<div class="g-stat"><b>${esc(v)}</b><span>${label}</span></div>`).join('');
    const total = (a.hackWeights || []).reduce((s, h) => s + (Number(h.weight) || 0), 0) || 1;
    const hacks = (a.hackWeights || []).map(h => `${h.hack} ${Math.round(h.weight / total * 1000) / 10}%`).join(', ');
    const resource = a.resourceType === 'file' ? `<a href="/mvc/assignments/tracker-v2/file/${a.id}">${esc(a.resourceFilename || 'Download')}</a>`
      : a.resourceUrl ? `<a href="${esc(a.resourceUrl)}" target="_blank" rel="noopener">${esc(a.resourceUrl)}</a>` : '';
    $('amDetails').innerHTML = [
      ['Category', esc(a.type)],
      ['Submission type', esc(a.assignmentType)],
      ['Points', esc(a.points)],
      ['Due', esc(a.dueDate)],
      ['Created', esc(a.timestamp)],
      ['Courses', esc((a.courseCodes || []).join(', '))],
      ['Hack weights', esc(hacks)],
      ['Resource', resource],
      ['Description', esc(a.description)],
    ].filter(([, v]) => v).map(([k, v]) => `<tr><th>${k}</th><td>${v}</td></tr>`).join('');
    $('amRubric').textContent = a.aiRubric || '(no rubric)';
    $('amRubricBadge').innerHTML = a.rubricReady ? '<span class="g-badge ok">AI-generated</span>'
      : '<span class="g-badge warn">Default / not ready</span>';
  }

  function showTab(tab) {
    document.querySelectorAll('#assignmentModal .g-tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tab));
    document.querySelectorAll('#assignmentModal [data-panel]').forEach(p => { p.style.display = p.dataset.panel === tab ? '' : 'none'; });
    if (tab === 'submissions') loadSubmissions();
    if (tab === 'edit') fillEditForm();
    if (tab === 'rubric') { $('rubricText').value = current.aiRubric || ''; $('rubricStatus').textContent = ''; }
    if (tab === 'more') showMore('checks');
  }

  // ---------- submissions tab ----------
  async function loadSubmissions() {
    $('subDetail').style.display = 'none';
    $('subList').style.display = '';
    $('subRows').innerHTML = '<tr><td colspan="4" class="g-empty">Loading…</td></tr>';
    try {
      const [subs, queue] = await Promise.all([
        api(`/api/submissions/assignment/${current.id}`),
        api('/api/submissions/ai-queue').catch(() => ({ entries: [] })),
      ]);
      submissions = subs;
      queueEta = Object.fromEntries(queue.entries.map(e => [e.submissionId, e.etaSeconds]));
      renderSubmissions();
    } catch (e) {
      $('subRows').innerHTML = `<tr><td colspan="4" class="g-empty">Could not load submissions: ${esc(e.message)}</td></tr>`;
    }
  }

  function submitterName(s) {
    return s.submitter?.name || (s.isGroup ? 'Group' : 'Unknown');
  }

  function renderSubmissions() {
    const needle = $('subSearch').value.trim().toLowerCase();
    const rows = submissions.filter(s => !needle || submitterName(s).toLowerCase().includes(needle));
    if (!rows.length) {
      $('subRows').innerHTML = '<tr><td colspan="4" class="g-empty">No submissions.</td></tr>';
      return;
    }
    $('subRows').innerHTML = rows.map(s => {
      const eta = queueEta[s.id];
      const status = eta != null ? `<span class="g-badge warn">AI queue · ${esc(formatEta(eta))}</span>`
        : s.grade != null ? '<span class="g-badge ok">Graded</span>' : '<span class="g-badge muted">Ungraded</span>';
      const overridden = s.grading?.manualOverride;
      // Closed assignments only offer a teacher review.
      const actions = current.closedAt
        ? '<button class="g-btn" type="button" data-sub="review">Review</button>'
        : `<input type="number" class="g-grade-input" step="0.01" min="0" max="${current.points ?? 1}" value="${s.grade ?? ''}" placeholder="/ ${current.points ?? 1}" aria-label="Grade">
          <button class="g-btn" type="button" data-sub="grade">Save</button>
          <button class="g-btn" type="button" data-sub="ai">AI grade</button>
          ${overridden ? '<button class="g-btn" type="button" data-sub="clear" title="Remove the instructor grade so the AI grade applies">Use AI grade</button>' : ''}`;
      return `<tr data-id="${s.id}">
        <td>${esc(submitterName(s))}${s.isLate ? ' <span class="g-badge bad">Late</span>' : ''}</td>
        <td class="text-nowrap">${esc(formatGrade(s.grade, current.points))} ${gradeSourceBadge(s.grading)}</td>
        <td>${status}</td>
        <td class="text-nowrap">
          <button class="g-btn" type="button" data-sub="view">View</button>
          ${actions}
        </td></tr>`;
    }).join('');
  }

  function showSubmission(id) {
    const s = submissions.find(x => x.id === id);
    if (!s) return;
    $('subDetailBody').innerHTML = submissionDetailHtml(s, submitterName(s), current.points);
    $('subList').style.display = 'none';
    $('subDetail').style.display = '';
  }

  async function gradeRow(row, action, button) {
    const id = Number(row.dataset.id);
    const revision = submissions.find(s => s.id === id)?.grading?.revision;
    button.disabled = true;
    try {
      let result;
      if (action === 'grade') {
        const grade = row.querySelector('.g-grade-input').value;
        if (grade === '') throw new Error('Enter a grade first');
        result = await saveManualGrade(id, grade, revision);
      } else if (action === 'clear') {
        result = await clearManualGrade(id, revision);
      } else {
        button.textContent = 'Grading…';
        result = await requestAiGrade(id);
      }
      const aiGrade = result.grading?.aiGrade;
      toast(gradingOutcomeText(result.outcome)
        + (action === 'ai' && aiGrade != null ? ` AI grade: ${formatGrade(aiGrade, current.points)}` : ''));
      await loadSubmissions();
      refreshCurrent();
    } catch (e) {
      toast(e.message, false);
      button.disabled = false;
      if (action === 'ai') button.textContent = 'AI grade';
    }
  }

  // Reload the list in the background so stats/badges stay in sync with the window.
  async function refreshCurrent() {
    const id = current?.id;
    await loadAssignments();
    if (id != null) {
      current = assignments.find(a => a.id === id) || null;
      if (current) renderHeader();
    }
  }

  // ---------- closing ----------
  async function closeCurrent() {
    const a = current;
    const pending = a.pendingAiCount ? ` ${a.pendingAiCount} submission(s) are still waiting for AI grading and will be finalized as they are.` : '';
    if (!confirm(`Close "${a.name}"? Every grade becomes final and is copied to the gradebook.${pending} This cannot be undone.`)) return;
    $('closeAssignment').disabled = true;
    try {
      const result = await closeAssignment(a.id);
      const s = result.summary;
      toast(`${gradingOutcomeText(result.outcome)} ${s.graded} graded, ${s.ungraded} ungraded, ${s.transferred} gradebook grade(s) written.`);
      await refreshCurrent();
      await loadSubmissions();
    } catch (e) {
      toast(e.message, false);
      $('closeAssignment').disabled = false;
    }
  }

  // ---------- teacher review (after close) ----------
  let reviewSubmission = null;
  let openReviewId = null;

  async function openReview(submissionId) {
    reviewSubmission = submissions.find(s => s.id === submissionId);
    if (!reviewSubmission) return;
    $('reviewSubtitle').textContent = `${submitterName(reviewSubmission)} · final grade `
      + formatGrade(reviewSubmission.grade, current.points);
    $('reviewReason').innerHTML = Object.entries(REVIEW_REASONS)
      .map(([value, label]) => `<option value="${value}">${esc(label)}</option>`).join('');
    $('reviewGrade').max = current.points ?? 1;
    openModal('reviewModal');
    await renderReviews();
  }

  async function renderReviews() {
    let list;
    try {
      list = await reviews.list(reviewSubmission.id);
    } catch (e) {
      $('reviewHistory').innerHTML = `<span class="g-sub">${esc(e.message)}</span>`;
      return;
    }
    const open = list.find(r => r.status === 'OPEN');
    openReviewId = open ? open.id : null;
    $('reviewOpenForm').style.display = open ? 'none' : '';
    $('reviewOpen').style.display = open ? '' : 'none';
    if (open) {
      $('reviewOpenedBy').textContent = `${REVIEW_REASONS[open.reason]} · opened by ${open.openedBy || 'a teacher'} · grade then ${formatGrade(open.gradeAtOpen, current.points)}`;
      $('reviewEvidence').value = open.evidence || '';
      $('reviewEvidenceDetail').innerHTML = evidenceHtml(open);
    }
    const decided = list.filter(r => r.status === 'DECIDED').reverse();
    $('reviewHistory').innerHTML = decided.length ? decided.map(r => `<div class="g-panel">
        <b>${esc(REVIEW_REASONS[r.reason])}</b> · ${esc(REVIEW_DECISIONS[r.decision])}
        ${r.decision === 'ADJUST' ? ` to ${esc(formatGrade(r.adjustedGrade, current.points))}` : ''}
        <div class="g-sub">${esc(r.decidedBy || 'A teacher')} · ${esc(new Date(r.decidedAt).toLocaleString())}</div>
        <div style="white-space:pre-wrap">${esc(r.rationale)}</div></div>`).join('')
      : '<span class="g-sub">None.</span>';
  }

  function evidenceHtml(review) {
    const parts = [];
    if (review.aiGrade != null) {
      parts.push(`<div><b>AI re-evaluation:</b> ${esc(formatGrade(review.aiGrade, current.points))}
        <div class="g-sub" style="white-space:pre-wrap">${esc(review.aiFeedback)}</div></div>`);
    }
    if (review.similarSubmissions) {
      parts.push(review.similarSubmissions.length
        ? `<div><b>Identical submissions:</b><ul class="mb-0">${review.similarSubmissions.map(m =>
            `<li>#${esc(m.submissionId)} ${esc(m.submitterName || '')}: ${esc(m.basis.map(b => SIMILARITY_BASIS[b] || b).join(', '))}</li>`).join('')}</ul>
            <div class="g-sub">Exact matches only: a reason to look, not proof of copying.</div></div>`
        : '<div><b>Identical submissions:</b> none found.</div>');
    }
    return parts.join('');
  }

  async function reviewAction(button, action) {
    button.disabled = true;
    try {
      const result = await action();
      toast(gradingOutcomeText(result.outcome, result.detail));
      await renderReviews();
      return result;
    } catch (e) {
      toast(e.message, false);
    } finally {
      button.disabled = false;
    }
  }

  async function decideReview(event) {
    event.preventDefault();
    const decision = document.querySelector('input[name="reviewDecision"]:checked').value;
    const body = { decision, rationale: $('reviewRationale').value };
    if (decision === 'ADJUST') {
      if ($('reviewGrade').value === '') { toast('Enter the corrected grade', false); return; }
      body.grade = Number($('reviewGrade').value);
      body.feedback = $('reviewFeedback').value;
    }
    const result = await reviewAction(event.submitter || $('reviewDecisionForm').querySelector('button'),
      () => reviews.decide(openReviewId, body));
    if (result) {
      $('reviewDecisionForm').reset();
      $('reviewAdjustFields').style.display = 'none';
      await loadSubmissions();
      refreshCurrent();
    }
  }

  // ---------- edit tab ----------
  function fillEditForm() {
    const a = current;
    $('editName').value = a.name || '';
    $('editType').value = a.type || '';
    $('editPoints').value = a.points ?? '';
    $('editDue').value = toIsoDate(a.dueDate);
    $('editDescription').value = a.description || '';
    const types = [...new Set([...SUBMISSION_TYPES, (a.assignmentType || '').toLowerCase()].filter(Boolean))];
    $('editSubmissionType').innerHTML = types.map(t =>
      `<option value="${esc(t)}" ${t === (a.assignmentType || '').toLowerCase() ? 'selected' : ''}>${esc(t)}</option>`).join('');
    $('editStatus').textContent = a.dueDate && !toIsoDate(a.dueDate) ? `Current due date "${a.dueDate}" isn't MM/DD/YYYY; pick a new one.` : '';
  }

  async function saveEdit(event) {
    event.preventDefault();
    const payload = {
      name: $('editName').value.trim(),
      type: $('editType').value.trim(),
      points: Number($('editPoints').value),
      dueDate: fromIsoDate($('editDue').value),
      assignmentType: $('editSubmissionType').value,
      description: $('editDescription').value.trim(),
    };
    $('editStatus').textContent = 'Saving…';
    try {
      await api(`/api/assignments/${current.id}/details`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
      });
      $('editStatus').textContent = 'Saved.';
      toast('Assignment saved');
      await refreshCurrent();
    } catch (e) {
      $('editStatus').textContent = e.message;
    }
  }

  // ---------- rubric tab ----------
  async function saveRubric() {
    const rubric = $('rubricText').value;
    if (!rubric.trim()) { $('rubricStatus').textContent = 'A rubric is required.'; return; }
    $('rubricStatus').textContent = 'Saving…';
    try {
      await api(`/api/assignments/${current.id}/ai-rubric`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rubric }),
      });
      await refreshCurrent();
      $('rubricStatus').textContent = current.rubricReady ? 'Saved.'
        : 'Saved, but it is not on the points scale, so the AI will replace it.';
    } catch (e) {
      $('rubricStatus').textContent = e.message;
    }
  }

  async function regenerateRubric() {
    const button = $('regenRubric');
    button.disabled = true;
    $('rubricStatus').textContent = 'Generating… this takes about 10-20 seconds.';
    try {
      const result = await api(`/api/assignments/${current.id}/ai-rubric/regenerate`, { method: 'POST' });
      $('rubricText').value = result.aiRubric;
      $('rubricStatus').textContent = 'New rubric saved.';
      await refreshCurrent();
    } catch (e) {
      $('rubricStatus').textContent = e.message;
    } finally {
      button.disabled = false;
    }
  }

  // ---------- more tab ----------
  function showMore(panel) {
    document.querySelectorAll('[data-more-panel]').forEach(p => { p.style.display = p.dataset.morePanel === panel ? '' : 'none'; });
    if (panel === 'checks') renderChecks($('moreChecks'), current.id);
    if (panel === 'graders') loadGraders();
    if (panel === 'frontmatter') loadFrontmatter();
  }

  async function renderChecks(target, id) {
    target.innerHTML = '<div class="g-sub">Running checks…</div>';
    try {
      const checks = await api(`/api/assignments/${id}/checks`);
      const failed = checks.filter(c => !c.ok).length;
      target.innerHTML = `<div class="mb-2">${failed ? `<span class="g-badge bad">${failed} problem(s)</span>` : '<span class="g-badge ok">All checks passed</span>'}</div>
        <table class="g-details"><tbody>${checks.map(c => `<tr>
          <th>${c.ok ? '<span class="g-badge ok">OK</span>' : '<span class="g-badge bad">Fix</span>'} ${esc(c.check)}</th>
          <td>${c.ok ? '' : esc(c.message)}</td></tr>`).join('')}</tbody></table>`;
    } catch (e) {
      target.innerHTML = `<div class="g-sub">${esc(e.message)}</div>`;
    }
  }

  async function loadGraders() {
    $('graderResults').innerHTML = '';
    $('graderSearch').value = '';
    try {
      const list = await api(`/api/assignments/assignedGraders/${current.id}`);
      graders = new Map(list.map(p => [p.id, p]));
    } catch (e) {
      graders = new Map();
      toast(e.message, false);
    }
    renderGraders();
  }

  function renderGraders() {
    $('graderList').innerHTML = graders.size ? [...graders.values()].map(p =>
      `<button class="g-btn me-1 mb-1" type="button" data-remove="${p.id}" title="Remove">${esc(p.name)} &times;</button>`).join('')
      : '<span class="g-sub">No graders assigned.</span>';
  }

  const searchGraders = debounce(async q => {
    if (q.length < 2) { $('graderResults').innerHTML = ''; return; }
    try {
      const found = await api(`/api/synergy/gradebook/students?q=${encodeURIComponent(q)}`);
      $('graderResults').innerHTML = found.filter(p => !graders.has(p.id)).slice(0, 15).map(p =>
        `<button class="g-btn me-1 mb-1" type="button" data-add="${p.id}" data-name="${esc(p.name)}" data-uid="${esc(p.uid)}">+ ${esc(p.name)}</button>`).join('')
        || '<span class="g-sub">No students found.</span>';
    } catch (e) {
      $('graderResults').innerHTML = `<span class="g-sub">${esc(e.message)}</span>`;
    }
  }, 250);

  async function saveGraders() {
    try {
      await postJson(`/api/assignments/assignGraders/${current.id}`, [...graders.keys()]);
      toast('Graders saved');
    } catch (e) {
      toast(e.message, false);
    }
  }

  async function randomizeGraders() {
    if (!confirm('Randomly assign each submission another student to peer grade it? This replaces current submission graders.')) return;
    try {
      const message = await api(`/api/assignments/randomizeGraders/${current.id}`, { method: 'POST' });
      toast(typeof message === 'string' ? message : 'Graders randomized');
    } catch (e) {
      toast(e.message, false);
    }
  }

  async function loadFrontmatter() {
    $('frontmatterText').value = 'Loading…';
    try {
      const result = await api(`/api/assignments/${current.id}/frontmatter`);
      $('frontmatterText').value = result.frontmatter || '';
    } catch (e) {
      $('frontmatterText').value = e.message;
    }
  }

  async function deleteCurrent() {
    if (!confirm(`Delete "${current.name}" and all of its submissions? This can't be undone.`)) return;
    try {
      await api(`/api/assignments/delete/${current.id}`, { method: 'POST' });
      toast('Assignment deleted');
      closeModal($('assignmentModal'));
      current = null;
      await loadAssignments();
    } catch (e) {
      toast(e.message, false);
    }
  }

  // ---------- CSV export ----------
  function downloadCsv(filename, rows) {
    const cell = v => {
      const s = String(v ?? '');
      return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const blob = new Blob([rows.map(r => r.map(cell).join(',')).join('\n')], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
  }

  function assignmentCsvRows(list) {
    const headers = ['id', 'name', 'type', 'assignmentType', 'points', 'dueDate', 'timestamp', 'contentUrl', 'description', 'aiRubric'];
    return [headers, ...list.map(a => headers.map(h => a[h]))];
  }

  function exportSubmissions() {
    if (!submissions.length) { toast('No submissions to export', false); return; }
    const rows = [['submission_id', 'submitter_id', 'submitter_name', 'content', 'comment', 'grade', 'feedback', 'is_late']];
    submissions.forEach(s => rows.push([s.id, s.submitter?.id, submitterName(s), contentText(s.content), s.comment, s.grade, s.feedback, !!s.isLate]));
    downloadCsv(`submissions_assignment_${current.id}.csv`, rows);
  }

  // ---------- test tools ----------
  function fillCheckSelect() {
    const selected = $('checkSelect').value;
    $('checkSelect').innerHTML = assignments.map(a => `<option value="${a.id}">${esc(a.name)}</option>`).join('');
    if (selected) $('checkSelect').value = selected;
  }

  async function createTest() {
    $('createTestStatus').textContent = 'Creating…';
    try {
      const created = await api('/api/assignments/test', { method: 'POST' });
      $('createTestStatus').textContent = `Created "${created.name}" (#${created.id}). Its AI rubric generates in the background.`;
      await loadAssignments();
      $('checkSelect').value = String(created.id);
    } catch (e) {
      $('createTestStatus').textContent = e.message;
    }
  }

  async function checkAll() {
    const target = $('checkResults');
    const button = $('runAllChecks');
    button.disabled = true;
    const problems = [];
    let done = 0;
    const queue = [...assignments];
    async function worker() {
      while (queue.length) {
        const a = queue.shift();
        try {
          const checks = await api(`/api/assignments/${a.id}/checks`);
          const failed = checks.filter(c => !c.ok);
          if (failed.length) problems.push({ a, failed });
        } catch (e) {
          problems.push({ a, failed: [{ check: 'Request', message: e.message }] });
        }
        target.innerHTML = `<div class="g-sub">Checked ${++done} of ${assignments.length}…</div>`;
      }
    }
    await Promise.all([1, 2, 3, 4].map(worker));
    button.disabled = false;
    problems.sort((x, y) => (x.a.name || '').localeCompare(y.a.name || ''));
    target.innerHTML = problems.length
      ? `<div class="mb-2"><span class="g-badge bad">${problems.length} assignment(s) with problems</span></div>
         <div style="max-height:320px;overflow:auto">${problems.map(p => `<div class="g-panel">
           <b>${esc(p.a.name)}</b> <span class="g-sub">#${p.a.id}</span>
           <ul class="mb-0">${p.failed.map(c => `<li>${esc(c.check)}: ${esc(c.message)}</li>`).join('')}</ul></div>`).join('')}</div>`
      : '<span class="g-badge ok">Every assignment passed</span>';
  }

  // ---------- wiring ----------
  $('assignmentSearch').addEventListener('input', debounce(renderList, 150));
  $('filterPanel').addEventListener('input', renderList);
  $('filterToggle').addEventListener('click', () => {
    const drop = $('filterDrop');
    drop.classList.toggle('open');
    $('filterToggle').setAttribute('aria-expanded', drop.classList.contains('open'));
  });
  document.addEventListener('click', e => { if (!$('filterDrop').contains(e.target)) $('filterDrop').classList.remove('open'); });
  $('clearFilters').addEventListener('click', () => {
    document.querySelectorAll('#filterPanel input').forEach(i => { if (i.type === 'checkbox') i.checked = false; else i.value = ''; });
    renderList();
  });

  $('assignmentRows').addEventListener('click', e => {
    const row = e.target.closest('tr[data-id]');
    if (row) openAssignment(Number(row.dataset.id));
  });
  $('assignmentRows').addEventListener('keydown', e => {
    const row = e.target.closest('tr[data-id]');
    if (row && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openAssignment(Number(row.dataset.id)); }
  });

  document.querySelectorAll('.g-modal').forEach(modal => {
    modal.addEventListener('click', e => { if (e.target === modal || e.target.closest('[data-close]')) closeModal(modal); });
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') document.querySelectorAll('.g-modal.open').forEach(closeModal);
  });

  document.querySelectorAll('#assignmentModal .g-tab').forEach(t => t.addEventListener('click', () => showTab(t.dataset.tab)));
  $('subSearch').addEventListener('input', debounce(renderSubmissions, 150));
  $('subRows').addEventListener('click', e => {
    const button = e.target.closest('button[data-sub]');
    if (!button) return;
    const row = button.closest('tr');
    if (button.dataset.sub === 'view') showSubmission(Number(row.dataset.id));
    else if (button.dataset.sub === 'review') openReview(Number(row.dataset.id));
    else gradeRow(row, button.dataset.sub, button);
  });
  $('subBack').addEventListener('click', () => { $('subDetail').style.display = 'none'; $('subList').style.display = ''; });
  $('exportSubs').addEventListener('click', exportSubmissions);

  $('editForm').addEventListener('submit', saveEdit);
  $('saveRubric').addEventListener('click', saveRubric);
  $('regenRubric').addEventListener('click', regenerateRubric);

  document.querySelectorAll('[data-more]').forEach(b => b.addEventListener('click', () => showMore(b.dataset.more)));
  $('graderSearch').addEventListener('input', e => searchGraders(e.target.value.trim()));
  $('graderResults').addEventListener('click', e => {
    const b = e.target.closest('button[data-add]');
    if (!b) return;
    graders.set(Number(b.dataset.add), { id: Number(b.dataset.add), name: b.dataset.name, uid: b.dataset.uid });
    b.remove();
    renderGraders();
  });
  $('graderList').addEventListener('click', e => {
    const b = e.target.closest('button[data-remove]');
    if (!b) return;
    graders.delete(Number(b.dataset.remove));
    renderGraders();
  });
  $('saveGraders').addEventListener('click', saveGraders);
  $('randomizeGraders').addEventListener('click', randomizeGraders);
  $('copyFrontmatter').addEventListener('click', () => {
    navigator.clipboard?.writeText($('frontmatterText').value).then(() => toast('Copied'), () => toast('Copy failed', false));
  });
  $('exportAssignment').addEventListener('click', () => downloadCsv(`assignment_${current.id}.csv`, assignmentCsvRows([current])));
  $('deleteAssignment').addEventListener('click', deleteCurrent);
  $('closeAssignment').addEventListener('click', closeCurrent);

  $('reviewOpenForm').addEventListener('submit', e => {
    e.preventDefault();
    reviewAction(e.submitter || $('reviewOpenForm').querySelector('button'),
      () => reviews.open(reviewSubmission.id, $('reviewReason').value, $('reviewOpenEvidence').value))
      .then(() => { $('reviewOpenEvidence').value = ''; });
  });
  $('reviewSaveEvidence').addEventListener('click', e =>
    reviewAction(e.currentTarget, () => reviews.saveEvidence(openReviewId, $('reviewEvidence').value)));
  $('reviewAiEvaluate').addEventListener('click', e => {
    e.currentTarget.textContent = 'Evaluating…';
    const button = e.currentTarget;
    reviewAction(button, () => reviews.aiEvaluation(openReviewId)).finally(() => { button.textContent = 'AI re-evaluation'; });
  });
  $('reviewSimilarity').addEventListener('click', e => reviewAction(e.currentTarget, () => reviews.similarity(openReviewId)));
  document.querySelectorAll('input[name="reviewDecision"]').forEach(radio => radio.addEventListener('change', () => {
    $('reviewAdjustFields').style.display = radio.value === 'ADJUST' && radio.checked ? '' : 'none';
  }));
  $('reviewDecisionForm').addEventListener('submit', decideReview);

  $('exportAll').addEventListener('click', () => downloadCsv('assignments.csv', assignmentCsvRows(assignments)));
  $('openTestTools').addEventListener('click', () => openModal('testModal'));
  $('createTest').addEventListener('click', createTest);
  $('runCheck').addEventListener('click', () => { if ($('checkSelect').value) renderChecks($('checkResults'), $('checkSelect').value); });
  $('runAllChecks').addEventListener('click', checkAll);

  loadAssignments();
})();
