'use strict';
const $ = (id) => document.getElementById(id);
const uid = () => crypto.randomUUID();
let conversationId = uid();
let identity = { key: 'demo-key', tenant: 'tenant-a', user: 'user-001' };
let busy = false;
let pendingConfirmation = null;
let devices = [];
let selectedDevice = '';
let deviceLoadVersion = 0;
let ticketPage = 0;
let ticketLoadVersion = 0;

async function loadTickets() {
  const version = ++ticketLoadVersion;
  $('tickets-list').replaceChildren();
  $('tickets-notice').textContent = '正在加载工单…';
  ['tickets-previous', 'tickets-next', 'tickets-refresh'].forEach(id => { $(id).disabled = true; });
  try {
    const tickets = await SupportClient.json(`/api/devices/${encodeURIComponent(selectedDevice)}/tickets?page=${ticketPage}&size=10`, {
      headers: { 'X-API-Key': identity.key }, signal: AbortSignal.timeout(8000)
    });
    if (version !== ticketLoadVersion) return;
    $('tickets-notice').textContent = `${selectedDevice} · 第 ${ticketPage + 1} 页${tickets.length ? '' : ' · 暂无工单记录'}`;
    for (const ticket of tickets) {
      const card = document.createElement('article');
      card.className = 'ticket-card';
      const title = document.createElement('strong');
      title.textContent = ticket.status === 'OPEN' ? '待处理' : ticket.status;
      const issue = document.createElement('p');
      issue.textContent = ticket.issue;
      const metadata = document.createElement('small');
      metadata.textContent = `${new Date(ticket.createdAt).toLocaleString()} · ${ticket.id}`;
      card.append(title, issue, metadata);
      $('tickets-list').append(card);
    }
    $('tickets-next').disabled = tickets.length < 10;
  } catch (error) {
    if (version === ticketLoadVersion) $('tickets-notice').textContent = error.message;
  } finally {
    if (version === ticketLoadVersion) {
      $('tickets-previous').disabled = ticketPage === 0;
      $('tickets-refresh').disabled = false;
    }
  }
}
$('tickets-open').onclick = () => {
  if (!selectedDevice) { addMessage('请先选择设备，再查看工单记录。'); return; }
  ticketPage = 0;
  $('tickets-dialog').showModal();
  loadTickets();
};
$('tickets-close').onclick = () => $('tickets-dialog').close();
$('tickets-dialog').addEventListener('close', () => { ticketLoadVersion++; });
$('tickets-refresh').onclick = loadTickets;
$('tickets-previous').onclick = () => { ticketPage--; loadTickets(); };
$('tickets-next').onclick = () => { ticketPage++; loadTickets(); };

function renderDevice() {
  const device = devices.find(item => item.id === selectedDevice);
  $('device-summary').textContent = device
    ? `${device.status} · 电量 ${device.batteryLevel}% · 主刷寿命 ${device.brushLifePercent}%${device.errorCode ? ' · 故障 ' + device.errorCode : ''}`
    : '当前账号暂无设备，可以先体验知识问答。';
}

async function loadDevices() {
  const version = ++deviceLoadVersion;
  const key = identity.key;
  devices = [];
  selectedDevice = '';
  $('device-select').replaceChildren(new Option('正在加载…', ''));
  $('device-select').disabled = true;
  $('device-summary').textContent = '';
  try {
    const data = await SupportClient.json('/api/devices', { headers: { 'X-API-Key': key } });
    if (version !== deviceLoadVersion) return;
    devices = data;
    selectedDevice = devices[0]?.id || '';
    $('device-select').replaceChildren(...(devices.length
      ? devices.map(d => new Option(`${d.model} · ${d.id}`, d.id)) : [new Option('暂无设备', '')]));
    $('device-select').disabled = busy || !devices.length;
    renderDevice();
  } catch (error) {
    if (version !== deviceLoadVersion) return;
    $('device-select').replaceChildren(new Option('加载失败', ''));
    $('device-summary').textContent = error.message;
  }
}
$('device-select').onchange = () => { selectedDevice = $('device-select').value; resetChat(); renderDevice(); };
$('devices-refresh').onclick = () => { resetChat(); loadDevices(); };

function resetChat() {
  pendingConfirmation = null;
  conversationId = uid();
  $('messages').replaceChildren();
  $('welcome').hidden = false;
  $('message').value = '';
  $('message').focus();
}

function setBusy(value) {
  busy = value;
  $('device-select').disabled = value || !devices.length;
  $('devices-refresh').disabled = value;
  document.querySelectorAll('[data-prompt], #send, #new-chat, #settings-open, .action').forEach(el => { el.disabled = value; });
  $('message').disabled = value;
  $('send').textContent = value ? '思考中…' : '发送 ↑';
}

function scrollBottom() { $('conversation').scrollTop = $('conversation').scrollHeight; }

function addMessage(text, role = 'assistant') {
  $('welcome').hidden = true;
  const row = document.createElement('article');
  row.className = `message ${role}`;
  const icon = document.createElement('span');
  icon.className = 'message-icon';
  icon.textContent = role === 'user' ? 'U' : '✳';
  const content = document.createElement('div');
  content.className = 'message-content';
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text;
  content.append(bubble);
  row.append(icon, content);
  $('messages').append(row);
  scrollBottom();
  return { row, content, bubble };
}

function addConfirmation(content, request) {
  const button = document.createElement('button');
  button.className = 'action';
  button.textContent = '核对并确认创建工单';
  button.addEventListener('click', () => {
    pendingConfirmation = { request, button };
    $('confirm-text').textContent = request.message;
    $('confirm').showModal();
  });
  content.append(button);
}

async function send(message, confirmedRequest = null) {
  if (busy || !message.trim()) return;
  const request = confirmedRequest || { conversationId, message: SupportClient.devicePrompt(message.trim(), selectedDevice), confirmWrite: false, idempotencyKey: uid() };
  if (request.message.length > 4000) { addMessage('消息过长，请缩短到 3900 字以内后重试。'); return; }
  addMessage(request.confirmWrite ? `确认提交工单：${request.message}` : request.message, 'user');
  $('message').value = '';
  setBusy(true);
  const reply = addMessage('正在查询，请稍等…');
  try {
    const data = await SupportClient.json('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-API-Key': identity.key },
      body: JSON.stringify(request),
      signal: AbortSignal.timeout(60000)
    });
    if (typeof data.answer !== 'string') throw new Error('接口返回格式异常，请检查后端日志。');
    reply.bubble.textContent = data.answer;
    if (data.citations?.length) {
      const details = document.createElement('details');
      details.className = 'sources';
      const summary = document.createElement('summary');
      summary.textContent = `参考来源 · ${data.citations.length} 条`;
      const list = document.createElement('ul');
      data.citations.forEach(citation => {
        const item = document.createElement('li');
        item.textContent = `${citation.title} · ${citation.source}`;
        list.append(item);
      });
      details.append(summary, list);
      reply.content.append(details);
    }
    if (data.traceId) {
      const trace = document.createElement('div');
      trace.className = 'trace';
      trace.textContent = `TRACE ${data.traceId}`;
      reply.content.append(trace);
    }
    if (!request.confirmWrite && /工单|报修/.test(request.message + data.answer) && /确认/.test(data.answer)) addConfirmation(reply.content, request);
  } catch (error) {
    reply.row.classList.add('error');
    reply.bubble.textContent = error.name === 'TimeoutError'
      ? '等待回复超时。如果刚刚提交了工单，可以先查询工单状态；重试会沿用同一请求编号。'
      : error instanceof TypeError ? '连接失败，请确认 IDEA 中的服务仍在运行，然后重试。' : error.message;
    const retry = document.createElement('button');
    retry.className = 'action';
    retry.textContent = '重试这条请求';
    retry.onclick = () => {
      retry.remove();
      if (request.confirmWrite) {
        addConfirmation(reply.content, request);
      } else send(request.message, request);
    };
    reply.content.append(retry);
  } finally {
    setBusy(false);
    scrollBottom();
    $('message').focus();
  }
}

document.querySelectorAll('[data-prompt]').forEach(button => button.addEventListener('click', () => {
  const prompt = button.dataset.prompt;
  if (prompt.includes('device-001') && !selectedDevice) {
    addMessage('请先选择设备。没有设备时，可以先试试故障知识问答。');
    return;
  }
  send(prompt.replaceAll('device-001', selectedDevice));
}));
$('chat-form').addEventListener('submit', event => { event.preventDefault(); send($('message').value); });
$('message').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chat-form').requestSubmit(); }
});
$('new-chat').onclick = resetChat;
$('settings-open').onclick = () => {
  $('api-key').value = identity.key;
  $('tenant').value = identity.tenant;
  $('user').value = identity.user;
  $('settings').showModal();
};
$('settings-close').onclick = () => $('settings').close();
$('tenant').readOnly = true;
$('user').readOnly = true;
const settingsNotice = document.createElement('p');
settingsNotice.setAttribute('role', 'status');
$('settings-form').append(settingsNotice);
$('settings-form').querySelector('p').textContent = '访问密钥决定账号身份；租户和用户由服务器验证后返回。密钥仅保留在当前页面内存中。';
$('settings-form').onsubmit = async event => {
  event.preventDefault();
  const key = $('api-key').value.trim();
  if (!key) return;
  const submit = $('settings-form').querySelector('button[type="submit"], button.primary');
  submit.disabled = true;
  try {
    const account = await SupportClient.json('/api/session', { headers: { 'X-API-Key': key } });
    identity = { key, tenant: account.tenantId, user: account.userId };
  const tenantLabel = document.createElement('small');
  tenantLabel.textContent = identity.tenant;
  $('identity-label').replaceChildren(document.createTextNode(identity.user), tenantLabel);
  $('settings').close();
  resetChat();
  loadDevices();
    settingsNotice.textContent = '';
  } catch (error) { settingsNotice.textContent = error.message; }
  finally { submit.disabled = false; }
};
$('confirm-cancel').onclick = () => $('confirm').close();
$('confirm-submit').onclick = () => {
  if (!pendingConfirmation || busy) return;
  const { request, button } = pendingConfirmation;
  pendingConfirmation = null;
  $('confirm').close();
  button.remove();
  send(request.message, { ...request, confirmWrite: true });
};
fetch('/actuator/health', { signal: AbortSignal.timeout(8000) })
  .then(async response => {
    const data = await response.json();
    if (!response.ok || data.status !== 'UP') throw new Error('unavailable');
    $('health').textContent = '服务已连接';
    $('health').className = 'status online';
  })
  .catch(() => { $('health').textContent = '服务连接异常'; $('health').className = 'status offline'; });

async function initializeAccount() {
  setBusy(true);
  try {
    const account = await SupportClient.json('/api/session', { headers: { 'X-API-Key': identity.key } });
    identity = { ...identity, tenant: account.tenantId, user: account.userId };
    const tenant = document.createElement('small');
    tenant.textContent = identity.tenant;
    $('identity-label').replaceChildren(document.createTextNode(identity.user), tenant);
    await loadDevices();
  } catch (error) {
    $('identity-label').textContent = '账号未连接';
    $('device-select').replaceChildren(new Option('请配置访问密钥', ''));
    $('device-summary').textContent = error.message;
  } finally { setBusy(false); }
}
initializeAccount();
