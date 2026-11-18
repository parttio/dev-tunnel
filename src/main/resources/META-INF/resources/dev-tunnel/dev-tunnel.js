// Dev Tunnel: makes this app public through a Cloudflare quick tunnel, from a pill in the top-right corner.
// Added to localhost pages in dev mode by DevTunnelInitListener; talks to DevTunnelHandler on the server over
// Vaadin Copilot's connection. Rendered inside Copilot's shadow root, so it gets Copilot's styles and components.
// None of that is public Copilot API (checked with Copilot 25.3), so missing pieces are detected and reported.
(() => {
  const TERMS_URL = 'https://www.cloudflare.com/website-terms/';
  const LIMITS_URL =
    'https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/';
  // From Cloudflare's quick tunnel documentation and cloudflared's startup notice
  const LIMITS = [
    'For testing and development, not production',
    'No uptime guarantee',
    'Up to 200 requests in flight at a time',
    'No server-sent events',
    'A new link every time',
    'Anyone with the link can use the app',
  ];
  const CLOSE_ICON =
    '<svg viewBox="0 -960 960 960" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="m256-200-56-56 224-224-224-224 56-56 224 224 224-224 56 56-224 224 224 224-56 56-224-224-224 224Z"/></svg>';
  const STYLE = `
    #dev-tunnel {
      position: fixed; top: calc(env(safe-area-inset-top, 0px) + 0.75rem); right: 0.75rem; z-index: 2147483000;
      display: flex; flex-direction: column; align-items: flex-end; gap: 0.5rem; pointer-events: auto;
      font-family: var(--copilot-font-sans); font-size: var(--copilot-font-size-sm); color: var(--vaadin-text-color);
    }
    #dev-tunnel .dt-pill {
      display: flex; align-items: center; gap: 0.375rem; padding: 0.25rem 0.625rem; border: 0; cursor: pointer;
      border-radius: var(--copilot-toolbar-radius); background: var(--vaadin-background-color); color: inherit;
      box-shadow: var(--copilot-shadow-lg); font: inherit; font-size: var(--copilot-font-size-xs);
      font-weight: var(--copilot-font-weight-medium); line-height: var(--copilot-line-height-xs);
    }
    #dev-tunnel .dt-pill:focus-visible { outline: 2px solid var(--vaadin-focus-ring-color); }
    #dev-tunnel .dt-dot { width: 0.5rem; height: 0.5rem; border-radius: 50%; background: var(--vaadin-text-color-secondary); }
    #dev-tunnel .dt-pill[data-status="ON"] .dt-dot { background: var(--teal-9, #12a594); }
    #dev-tunnel .dt-pill[data-status="STARTING"] .dt-dot,
    #dev-tunnel .dt-pill[data-status="DOWNLOADING"] .dt-dot { background: var(--amber-9, #ffc53d); }
    #dev-tunnel .dt-popover {
      box-sizing: border-box; width: min(360px, calc(100vw - 1.5rem)); max-height: calc(100vh - 4.5rem); overflow: auto;
      border-radius: var(--vaadin-radius-l); background: var(--vaadin-background-color); box-shadow: var(--copilot-shadow-xl);
    }
    #dev-tunnel .dt-header {
      display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0.5rem 0 1rem;
      font-size: var(--copilot-font-size-xs); font-weight: var(--copilot-font-weight-bold); text-transform: uppercase;
    }
    #dev-tunnel .dt-header .dt-title { flex: 1; }`;

  let state = { status: 'OFF', remoteTabs: 0 };
  let open = false;
  let confirmingDisconnect = false;
  let root;
  let pill; // to give focus back when the popover closes

  // Copilot's switch, or a plain checkbox if this Copilot version has no such element
  const copilotToggle = () => !!customElements.get('copilot-toggle-button');
  const toggle = (id, labelId, checked) =>
    copilotToggle()
      ? `<copilot-toggle-button id="${id}" accessible-name-ref="${labelId}" ${checked ? 'checked' : ''}></copilot-toggle-button>`
      : `<input type="checkbox" role="switch" id="${id}" aria-labelledby="${labelId}" ${checked ? 'checked' : ''}>`;
  const toggleEvent = () => (copilotToggle() ? 'on-change' : 'change');

  const bus = () => window.Vaadin.copilot.eventbus;
  const send = (command, data = {}) => bus().send(command, data);
  const escape = (text) => String(text ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
  const tabs = (n) => `${n} remote tab${n === 1 ? '' : 's'}`;
  const port = () => Number(location.port) || (location.protocol === 'https:' ? 443 : 80);

  function update(next) {
    state = next;
    if (state.status !== 'ON' || state.remoteTabs === 0) {
      confirmingDisconnect = false;
    }
    render();
  }

  function render() {
    // Re-rendering replaces the elements; keep the focus where it was
    const focusedId = root.getRootNode().activeElement?.id;
    const { status } = state;
    const pillText = {
      ON: `Remote · ${state.remoteTabs}`,
      STARTING: 'Remote…',
      DOWNLOADING: 'Remote…',
    }[status] ?? 'Remote';
    root.innerHTML = `
      <style>${STYLE}</style>
      <button class="dt-pill" data-status="${status}" aria-expanded="${open}" aria-controls="dev-tunnel-popover"
        title="${status === 'ON' ? `${escape(state.url)}: ${tabs(state.remoteTabs)} open` : 'Make this app public'}">
        <span class="dt-dot"></span>${pillText}
      </button>
      ${open ? popover() : ''}`;
    pill = root.querySelector('.dt-pill');
    pill.addEventListener('click', () => setOpen(!open));
    if (open) {
      bindPopover();
    }
    if (focusedId) {
      root.querySelector(`#${focusedId}`)?.focus();
    }
  }

  function setOpen(value) {
    open = value;
    render();
    // Into the popover when it opens, back to the pill when it closes
    (open ? root.querySelector('#dev-tunnel-enable') : pill).focus();
  }

  function popover() {
    const { status, url, qrCode, error, remoteTabs } = state;
    const on = status !== 'OFF' && status !== 'FAILED';
    const progress = {
      DOWNLOADING: 'Downloading cloudflared (about 40 MB). This happens only the first time…',
      STARTING: 'Starting the Cloudflare tunnel…',
      FAILED: `Could not connect: ${escape(error)}`,
    }[status];
    return `
      <div class="dt-popover" id="dev-tunnel-popover" role="dialog" aria-labelledby="dev-tunnel-title">
        <div class="dt-header">
          <span class="dt-title" id="dev-tunnel-title">Dev Tunnel</span>
          ${status === 'ON' ? `<span class="text-secondary">${tabs(remoteTabs)}</span>` : ''}
          <vaadin-button id="dev-tunnel-close" theme="icon tertiary" aria-label="Close">${CLOSE_ICON}</vaadin-button>
        </div>
        <div class="flex flex-col px-4 py-0.5">
          <div class="flex flex-col divide-y">
            ${status === 'ON' ? connected(url, qrCode, remoteTabs) : activation()}
            <div class="flex gap-2 justify-between py-3.5">
              <label id="dev-tunnel-enable-label">Enable remote</label>
              ${toggle('dev-tunnel-enable', 'dev-tunnel-enable-label', on)}
            </div>
            ${progress ? `<div class="py-3.5 text-xs ${status === 'FAILED' ? '' : 'text-secondary'}" role="status">${progress}</div>` : ''}
            ${confirmingDisconnect ? confirmation(remoteTabs) : ''}
          </div>
        </div>
      </div>`;
  }

  function bindPopover() {
    const find = (id) => root.querySelector(`#dev-tunnel-${id}`);
    find('close').addEventListener('click', () => setOpen(false));
    find('enable').addEventListener(toggleEvent(), (e) => {
      if (e.target.checked) {
        send('dev-tunnel-enable', { port: port() });
      } else if (state.status === 'ON' && state.remoteTabs > 0) {
        confirmingDisconnect = true;
        render();
      } else {
        send('dev-tunnel-disable');
      }
    });
    find('cancel')?.addEventListener('click', () => {
      confirmingDisconnect = false;
      render();
    });
    find('disconnect')?.addEventListener('click', () => {
      confirmingDisconnect = false;
      render();
      send('dev-tunnel-disable');
    });
  }

  function activation() {
    return `
      <div class="flex flex-col gap-2 py-3.5">
        <span>Share this app with a public HTTPS link, through a Cloudflare quick tunnel.</span>
        <span class="text-xs text-secondary">Quick tunnel limits
          (<a href="${LIMITS_URL}" target="_blank" rel="noopener noreferrer">details</a>):</span>
        <ul class="text-xs text-secondary" style="margin: 0; padding-inline-start: 1.25em">
          ${LIMITS.map((limit) => `<li>${limit}</li>`).join('')}
        </ul>
        <span class="text-xs text-secondary">By enabling remote, you accept the
          <a href="${TERMS_URL}" target="_blank" rel="noopener noreferrer">Cloudflare Online Services Terms of Use</a>.
          Cloudflare may investigate the use of quick tunnels for violations.</span>
      </div>`;
  }

  function connected(url, qrCode, remoteTabs) {
    return `
      <div class="flex flex-col gap-2 py-3.5" style="align-items: center; text-align: center">
        <span class="text-xs text-secondary">Scan to open on your phone, or share the link:</span>
        <img src="${qrCode}" alt="QR code for ${escape(url)}" width="168" height="168"
          style="border-radius: var(--vaadin-radius-m); image-rendering: pixelated">
        <a href="${escape(url)}" target="_blank" rel="noopener noreferrer" style="overflow-wrap: anywhere">${escape(url)}</a>
        <span class="text-xs text-secondary">${tabs(remoteTabs)} open. Anyone with the link can use the app.</span>
      </div>`;
  }

  function confirmation(remoteTabs) {
    return `
      <div class="flex flex-col gap-2 py-3.5" role="alertdialog" aria-labelledby="dev-tunnel-confirm">
        <span id="dev-tunnel-confirm">${tabs(remoteTabs)} ${remoteTabs === 1 ? 'is' : 'are'} open.
          Disconnect ${remoteTabs === 1 ? 'it' : 'them'}?</span>
        <div class="flex gap-2" style="justify-content: flex-end">
          <vaadin-button id="dev-tunnel-cancel" theme="tertiary">Cancel</vaadin-button>
          <vaadin-button id="dev-tunnel-disconnect" theme="primary">Disconnect</vaadin-button>
        </div>
      </div>`;
  }

  // Closes on Escape and on clicks elsewhere on the page
  document.addEventListener('keydown', (e) => {
    if (open && e.key === 'Escape') {
      setOpen(false);
    }
  });
  document.addEventListener('click', (e) => {
    if (open && !e.composedPath().includes(root)) {
      open = false;
      render();
    }
  });

  // Copilot's shadow root holds its styles; its event bus carries the server's messages, buffered until heard
  const missingCopilot = setTimeout(() => console.info('Dev Tunnel needs Vaadin Copilot, which is not on this page.'
    + ' Copilot comes with vaadin-dev in dev mode; see https://vaadin.com/docs/latest/tools/copilot'), 15000);
  customElements.whenDefined('copilot-main').then(function attach() {
    const copilot = document.querySelector('copilot-main');
    if (!copilot?.shadowRoot) {
      setTimeout(attach, 100);
      return;
    }
    clearTimeout(missingCopilot);
    if (!window.Vaadin?.copilot?.eventbus) {
      console.warn('Dev Tunnel does not support this Vaadin Copilot version: it has no event bus.');
      return;
    }
    root = document.createElement('div');
    root.id = 'dev-tunnel';
    copilot.shadowRoot.append(root);
    render();
    bus().on('dev-tunnel-state', (event) => {
      event.preventDefault(); // handled
      update(event.detail);
    });
    send('dev-tunnel-state');
  });
})();
