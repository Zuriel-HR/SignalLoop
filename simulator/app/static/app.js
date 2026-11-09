const form = document.getElementById('scenarioForm');
const message = document.getElementById('formMessage');
const button = document.getElementById('generateButton');
const toast = document.getElementById('toast');
const ticketCount = document.getElementById('ticketCount');
let activeScenario = null;
let toastTimer;

const numberFormat = new Intl.NumberFormat('en-US');
const categoryNames = { hardware: 'Hardware', network: 'Red', software: 'Software', reconciliation: 'Conciliación', fraud: 'Señal de fraude' };

function escapeHTML(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function api(path, options) {
  const response = await fetch(path, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.detail || `Request failed (${response.status})`);
  return data;
}

function showToast(text) {
  toast.textContent = text;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 3400);
}

function updateSplitPreview() {
  const count = Number(ticketCount.value);
  const train = Math.floor(count * 0.5);
  const validation = Math.floor(count * 0.25);
  document.getElementById('trainPreview').textContent = train;
  document.getElementById('validationPreview').textContent = validation;
  document.getElementById('testPreview').textContent = count - train - validation;
}

function localizeError(text) {
  return String(text).replace('DATABASE_URL is not configured. Copy simulator/.env.example to simulator/.env and set your local connection.', 'Falta configurar DATABASE_URL. Copia simulator/.env.example a simulator/.env y agrega tu conexión local.')
    .replace('PostgreSQL is unavailable. Start Postgres.app and verify DATABASE_URL.', 'PostgreSQL no está disponible. Inicia Postgres.app y revisa DATABASE_URL.')
    .replace('Select at least one incident category.', 'Selecciona al menos una categoría de incidente.')
    .replace('Select at least one signal source.', 'Selecciona al menos una fuente de señales.')
    .replace('The selected categories and signal sources do not produce any compatible fault templates.', 'Esa combinación de categorías y fuentes no tiene fallas compatibles. Selecciona otra fuente de señales.')
    .replace('Scenario not found.', 'No se encontró el escenario.')
    .replace(/Request failed \((\d+)\)/, 'La solicitud falló ($1).');
}

function setHealth(status, messageText) {
  const el = document.getElementById('dbStatus');
  el.className = `db-status ${status}`;
  el.querySelector('span:last-child').textContent = messageText;
}

async function refreshHealth() {
  try {
    const health = await api('/api/health');
    if (health.status === 'online') setHealth('online', `PostgreSQL · ${health.database}`);
    else setHealth('offline', 'PostgreSQL desconectado');
  } catch (_) {
    setHealth('offline', 'PostgreSQL desconectado');
  }
}

function renderScenarios(items) {
  const body = document.getElementById('scenarioRows');
  document.getElementById('navCount').textContent = items.length;
  document.getElementById('scenarioCount').textContent = items.length;
  document.getElementById('statScenarios').textContent = numberFormat.format(items.length);
  document.getElementById('statTickets').textContent = numberFormat.format(items.reduce((sum, item) => sum + item.ticket_count, 0));
  document.getElementById('statTests').textContent = numberFormat.format(items.reduce((sum, item) => sum + item.test_count, 0));
  if (!items.length) {
    body.innerHTML = '<tr><td class="empty-cell" colspan="8">Aún no hay escenarios. Genera tu primer conjunto sintético arriba.</td></tr>';
    return;
  }
  body.innerHTML = items.map((item) => `<tr>
    <td><div class="scenario-name">${escapeHTML(item.name)}</div><div class="scenario-key">${escapeHTML(item.scenario_key)}</div></td>
    <td class="num-cell">${numberFormat.format(item.ticket_count)}</td><td class="num-cell">${numberFormat.format(item.train_count)}</td>
    <td class="num-cell">${numberFormat.format(item.validation_count)}</td><td class="num-cell">${numberFormat.format(item.test_count)}</td>
    <td class="num-cell">${escapeHTML(item.seed)}</td><td><span class="status-pill">${item.status === 'generated' ? 'generado' : escapeHTML(item.status)}</span></td>
    <td><button class="view-button" data-view="${escapeHTML(item.id)}" data-name="${escapeHTML(item.name)}">Revisar →</button></td>
  </tr>`).join('');
  body.querySelectorAll('[data-view]').forEach((control) => control.addEventListener('click', () => loadCases(control.dataset.view, control.dataset.name)));
}

async function loadScenarios() {
  try {
    const items = await api('/api/scenarios');
    renderScenarios(items);
  } catch (error) {
    document.getElementById('scenarioRows').innerHTML = `<tr><td class="empty-cell" colspan="8">${escapeHTML(localizeError(error.message))}</td></tr>`;
    document.getElementById('statScenarios').textContent = '—';
    document.getElementById('statTickets').textContent = '—';
    document.getElementById('statTests').textContent = '—';
  }
}

async function loadCases(id, name) {
  activeScenario = id;
  document.getElementById('caseSection').classList.remove('hidden');
  document.getElementById('caseHeading').textContent = `${name} · casos`;
  document.getElementById('caseRows').innerHTML = '<tr><td class="empty-cell" colspan="6">Cargando casos…</td></tr>';
  document.getElementById('splitFilter').value = 'all';
  document.getElementById('caseSection').scrollIntoView({ behavior: 'smooth', block: 'start' });
  await refreshCases();
}

async function refreshCases() {
  if (!activeScenario) return;
  const split = document.getElementById('splitFilter').value;
  const body = document.getElementById('caseRows');
  try {
    const cases = await api(`/api/scenarios/${encodeURIComponent(activeScenario)}/tickets?split=${encodeURIComponent(split)}`);
    if (!cases.length) {
      body.innerHTML = '<tr><td class="empty-cell" colspan="6">No hay casos en esta división.</td></tr>';
      return;
    }
    body.innerHTML = cases.map((item) => `<tr>
      <td><div class="scenario-name">${escapeHTML(item.ticket_key)}</div><div class="scenario-key">${escapeHTML(item.title)}</div></td>
      <td>${escapeHTML(item.atm_key || '—')}<div class="scenario-key">${escapeHTML(item.location_code || '')}</div></td>
      <td><span class="category-pill ${escapeHTML(item.category)}">${escapeHTML(categoryNames[item.category] || item.category)}</span></td>
      <td><span class="severity-pill ${escapeHTML(item.severity.toLowerCase())}">${escapeHTML(item.severity)}</span></td>
      <td><span class="split-pill">${item.split === 'train' ? 'Entrenamiento' : item.split === 'validation' ? 'Validación' : 'Prueba'}</span></td>
      <td class="root-cause" title="${escapeHTML(item.root_cause)}">${escapeHTML(item.root_cause)}</td>
    </tr>`).join('');
  } catch (error) {
    body.innerHTML = `<tr><td class="empty-cell" colspan="6">${escapeHTML(localizeError(error.message))}</td></tr>`;
  }
}

ticketCount.addEventListener('change', updateSplitPreview);
document.getElementById('randomSeed').addEventListener('click', () => {
  const seed = Math.floor(Math.random() * 900000) + 100000;
  document.getElementById('seed').value = seed;
  document.getElementById('scenarioName').value = `Dispositivos · Semilla ${seed}`;
});
document.getElementById('splitFilter').addEventListener('change', refreshCases);

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  message.textContent = '';
  const payload = {
    name: document.getElementById('scenarioName').value.trim(),
    ticket_count: Number(ticketCount.value),
    seed: Number(document.getElementById('seed').value),
    categories: [...form.querySelectorAll('input[name="categories"]:checked')].map((item) => item.value),
    subsystems: [...form.querySelectorAll('input[name="subsystems"]:checked')].map((item) => item.value),
  };
  button.disabled = true;
  button.innerHTML = '<span class="spinner"></span> Generando…';
  try {
    const created = await api('/api/scenarios', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    message.textContent = '';
    showToast(`${created.ticket_count} casos sintéticos guardados en PostgreSQL.`);
    await loadScenarios();
    await loadCases(created.id, created.name);
  } catch (error) {
    message.textContent = localizeError(error.message);
    showToast(localizeError(error.message));
  } finally {
    button.disabled = false;
    button.innerHTML = '<span>✳</span> Generar escenario <span class="button-arrow">→</span>';
  }
});

updateSplitPreview();
refreshHealth();
loadScenarios();
