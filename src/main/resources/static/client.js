'use strict';
// Shared by the browser and Node's built-in tests; no build step required.
const SupportClient = {
  async json(url, options = {}) {
    const response = await fetch(url, { ...options, signal: options.signal || AbortSignal.timeout(8000) });
    if (!response.ok) {
      const messages = {
        401: '访问密钥无效，请打开连接设置。',
        403: '没有访问权限，请检查当前账号。',
        409: '请求发生冲突，请先核实当前会话或工单状态。',
        429: '请求过于频繁或服务繁忙，请稍后重试。',
        503: '服务暂时不可用；涉及报修时请先查询工单记录。'
      };
      const trace = response.headers.get('X-Trace-Id');
      const retryAfter = response.headers.get('Retry-After');
      throw new Error((messages[response.status] || `请求失败（HTTP ${response.status}）。`)
        + (retryAfter ? ` 建议等待 ${retryAfter} 后重试。` : '')
        + (trace ? ` 排查编号 TRACE ${trace}` : ''));
    }
    return response.json();
  },
  devicePrompt(message, deviceId) {
    return deviceId && !/device-\d+/.test(message) ? `设备 ${deviceId}：${message}` : message;
  }
};
if (typeof module !== 'undefined') module.exports = SupportClient;
