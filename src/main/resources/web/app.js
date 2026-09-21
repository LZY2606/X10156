'use strict';

let state = null;
let selectedEndpoint = null;

const $ = (id) => document.getElementById(id);

async function api(method, path, body) {
  const opts = { method, headers: { 'content-type': 'application/json' } };
  if (body !== undefined) opts.body = JSON.stringify(body);
  const res = await fetch(path, opts);
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = text; }
  if (!res.ok) throw new Error((data && data.error) || res.status);
  return data;
}

function toast(msg, isErr) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast show' + (isErr ? ' err' : '');
  setTimeout(() => { t.className = 'toast'; }, 2600);
}

function fmtTime(ms) {
  if (!ms) return '–';
  const d = new Date(ms);
  return d.toISOString().replace('T', ' ').replace('Z', '') + 'Z';
}
function esc(s) {
  return String(s ?? '').replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
}

async function refresh() {
  state = await api('GET', '/api/state');
  render();
}

function render() {
  $('clock').textContent = state.clock;
  $('clockIso').textContent = state.clockIso;
  $('schedCount').textContent = state.schedule.length;
  $('processedCount').textContent = state.processedCount;

  const epSel = $('evEndpoint');
  const prev = selectedEndpoint || epSel.value;
  epSel.innerHTML = '';
  state.endpoints.forEach(ep => {
    const o = document.createElement('option');
    o.value = ep.id; o.textContent = `${ep.id} — ${ep.name}`;
    epSel.appendChild(o);
  });
  if (prev && state.endpoints.some(e => e.id === prev)) {
    epSel.value = prev; selectedEndpoint = prev;
  } else if (state.endpoints.length) {
    selectedEndpoint = state.endpoints[0].id; epSel.value = selectedEndpoint;
  }

  $('endpoints').innerHTML = state.endpoints.map(ep => `
    <div class="ep-card">
      <div class="name">${esc(ep.name)} <code>${esc(ep.id)}</code>
        <span class="tag ${ep.paused ? 'paused' : 'active'}">${ep.paused ? '已暂停' : '运行中'}</span>
      </div>
      <div class="kv" style="margin-top:6px">行为 <b>${esc(ep.behavior)}</b>
        ${ep.behavior === 'STATUS' ? '· 状态 <b>' + ep.statusCode + '</b>' : ''}
        ${ep.behavior === 'RATE_LIMIT' ? '· Retry-After <b>' + esc(ep.retryAfter || '') + '</b>' : ''}
      </div>
      <div class="kv">初始 ${ep.initialBackoffMillis}ms · 倍数 ${ep.backoffMultiplier} ·
        上限 ${ep.maxBackoffMillis}ms · 最多 ${ep.maxAttempts} 次</div>
      <div class="row" style="margin-top:8px">
        <button class="secondary" onclick="selectEndpoint('${esc(ep.id)}')">编辑</button>
        ${ep.paused
          ? `<button class="good" onclick="setPaused('${esc(ep.id)}',false)">恢复</button>`
          : `<button class="danger" onclick="setPaused('${esc(ep.id)}',true)">暂停</button>`}
      </div>
    </div>`).join('') || '<p class="hint">还没有端点，先在左侧创建一个。</p>';

  const chains = [...state.chains].sort((a, b) => b.createdAt - a.createdAt);
  $('chains').innerHTML = chains.map(renderChain).join('')
    || '<p class="hint">还没有事件。发送一个事件后，用“单步推进”驱动虚拟时钟。</p>';
}

function renderChain(c) {
  const isOpen = c.status !== 'SUCCEEDED';
  const replay = c.replayOfChain
    ? `<div class="replay-note">↻ 重放自链 <code>${esc(c.replayOfChain)}</code>（深度 ${c.replayDepth}），旧链不可篡改</div>` : '';
  return `
  <div class="chain ${isOpen ? 'open' : ''}">
    <div class="chain-head" onclick="this.parentElement.classList.toggle('open')">
      <div>
        <span class="badge ${esc(c.status)}">${esc(c.status)}</span>
        <b>事件 ${esc(c.eventId)}</b> ${replay}
        <div class="ids">chain ${esc(c.chainId)} · 端点 ${esc(c.endpointId)} · ${c.attempts.length} 次尝试</div>
      </div>
      <div>
        ${c.status === 'DEAD_LETTER'
          ? `<button onclick="event.stopPropagation(); replay('${esc(c.chainId)}')">重放死信</button>` : ''}
      </div>
    </div>
    <div class="chain-body">
      <details><summary>原始 payload（${c.contentType}）</summary><pre>${esc(c.payload)}</pre></details>
      ${c.attempts.map(renderAttempt).join('')}
    </div>
  </div>`;
}

function renderAttempt(a) {
  let payloadText = '';
  try { payloadText = JSON.stringify(JSON.parse(atob(a.bodyBase64)), null, 2); }
  catch (_) { payloadText = atob(a.bodyBase64); }
  const ver = a.verification || {};
  const verHtml = Object.keys(ver).length ? `
    <div class="kv">签名验证：
      ${ver.valid ? '<span class="ok">✓ 有效</span>' : '<span class="no">✗ 无效</span>'}
      · ${esc(ver.algorithm || '')} · ${esc(ver.reason || '')}
    </div>` : '';
  const resp = a.responseStatus != null
    ? `<div class="kv">响应 <b>${a.responseStatus}</b>${a.responseBody ? '' : ''}</div>`
    : (a.error ? `<div class="kv"><span class="no">${esc(a.error)}</span></div>` : '');
  const next = a.nextReason ? `<div class="kv">调度：${esc(a.nextReason)}
      ${a.nextScheduledAt ? ' @ <b>' + a.nextScheduledAt + '</b>' : ''}</div>` : '';
  const headers = Object.entries(a.requestHeaders || {})
    .map(([k, v]) => `${esc(k)}: ${esc(v)}`).join('\n');
  const respHeaders = Object.entries(a.responseHeaders || {})
    .map(([k, v]) => `${esc(k)}: ${esc(v)}`).join('\n');
  return `
  <div class="attempt ${esc(a.status)}">
    <div class="meta">
      <span class="badge ${esc(a.status)}">#${a.seq} ${esc(a.status)}</span>
      <span>attempt ${esc(a.attemptId)}</span>
      <span>计划 ${a.scheduledAt}</span>
      <span>发送 ${a.dispatchedAt || '–'}</span>
      <span>落盘 ${a.resolvedAt || '–'}</span>
      ${a.retryAfterParsed ? `<span>Retry-After 解析：${esc(a.retryAfterParsed)}</span>` : ''}
    </div>
    ${verHtml}
    ${resp}
    ${next}
    <details><summary>规范化签名输入</summary><pre>${esc(a.signingInput)}</pre></details>
    <details><summary>签名值与请求头</summary>
      <div class="kv">signature: <code>${esc(a.signature || '')}</code></div>
      <pre>${headers}</pre>
    </details>
    <details><summary>实际发送字节（base64 / 文本，${a.bodyLength} bytes, sha256 ${esc(a.sha256 || '').slice(0,16)}…）</summary>
      <pre>${esc(a.bodyBase64)}</pre><pre>${esc(payloadText)}</pre>
    </details>
    ${respHeaders ? `<details><summary>响应头</summary><pre>${respHeaders}</pre></details>` : ''}
    ${a.responseBody ? `<details><summary>响应体</summary><pre>${esc(a.responseBody)}</pre></details>` : ''}
  </div>`;
}

window.selectEndpoint = function (id) {
  selectedEndpoint = id;
  const ep = state.endpoints.find(e => e.id === id);
  if (!ep) return;
  $('epId').value = ep.id; $('epName').value = ep.name;
  $('epSecret').value = ''; $('epKeyId').value = ep.keyId;
  $('epInitial').value = ep.initialBackoffMillis; $('epMult').value = ep.backoffMultiplier;
  $('epMax').value = ep.maxBackoffMillis; $('epAttempts').value = ep.maxAttempts;
  $('epBehavior').value = ep.behavior; $('epStatus').value = ep.statusCode;
  $('epRetry').value = ep.retryAfter || ''; $('epCovered').value = ep.coveredHeaders;
  toast('已载入端点配置，修改后点“更新选中端点”');
};

window.setPaused = async function (id, paused) {
  try {
    await api('POST', `/api/endpoints/${encodeURIComponent(id)}/${paused ? 'pause' : 'resume'}`, {});
    await refresh();
  } catch (e) { toast(e.message, true); }
};

window.replay = async function (chainId) {
  try {
    const c = await api('POST', `/api/chains/${encodeURIComponent(chainId)}/replay`, {});
    toast('已创建重放链 ' + c.chainId);
    await refresh();
  } catch (e) { toast(e.message, true); }
};

async function step() {
  await api('POST', '/api/clock/step', {});
  await refresh();
}
async function advance() {
  await api('POST', '/api/clock/advance', { millis: Number($('advanceMs').value || 0) });
  await refresh();
}

$('btnStep').onclick = () => step().catch(e => toast(e.message, true));
$('btnAdvance').onclick = () => advance().catch(e => toast(e.message, true));
$('btnRefresh').onclick = () => refresh().catch(e => toast(e.message, true));

$('btnCreateEp').onclick = async () => {
  try {
    const body = collectEndpoint(false);
    const ep = await api('POST', '/api/endpoints', body);
    selectedEndpoint = ep.id;
    toast('端点已创建'); await refresh();
  } catch (e) { toast(e.message, true); }
};
$('btnUpdateEp').onclick = async () => {
  const id = $('epId').value.trim();
  if (!id) return toast('请填写要更新的端点 id', true);
  try {
    await api('PUT', `/api/endpoints/${encodeURIComponent(id)}`, collectEndpoint(true));
    selectedEndpoint = id; toast('端点已更新'); await refresh();
  } catch (e) { toast(e.message, true); }
};

function collectEndpoint(isUpdate) {
  const body = {
    name: $('epName').value.trim(),
    keyId: $('epKeyId').value.trim(),
    initialBackoffMillis: Number($('epInitial').value),
    backoffMultiplier: Number($('epMult').value),
    maxBackoffMillis: Number($('epMax').value),
    maxAttempts: Number($('epAttempts').value),
    behavior: $('epBehavior').value,
    statusCode: Number($('epStatus').value),
    retryAfter: $('epRetry').value.trim(),
    coveredHeaders: $('epCovered').value.trim(),
  };
  if (!isUpdate) body.id = $('epId').value.trim();
  const secret = $('epSecret').value.trim();
  if (secret) body.secret = secret;
  return body;
}

$('btnSend').onclick = async () => {
  try {
    const body = {
      endpointId: $('evEndpoint').value,
      eventId: $('evId').value.trim() || null,
      payload: $('evPayload').value,
      contentType: 'application/json',
    };
    const ev = await api('POST', '/api/events', body);
    toast('事件已入队，chain ' + ev.chainId);
    $('evId').value = '';
    await refresh();
  } catch (e) { toast(e.message, true); }
};

$('btnSeed').onclick = async () => {
  try { await api('POST', '/api/admin/jitter-seed', { seed: Number($('seed').value) });
    toast('抖动种子已更新'); await refresh();
  } catch (e) { toast(e.message, true); }
};
$('btnCrash').onclick = async () => {
  try {
    const point = $('crashPoint').value;
    if (point) await api('POST', '/api/admin/crash', { point });
    else await api('POST', '/api/admin/clear-crash', {});
    toast(point ? '崩溃点已武装：' + point : '已清除'); await refresh();
  } catch (e) { toast(e.message, true); }
};

refresh().catch(e => toast(e.message, true));
