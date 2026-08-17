// Phase 8 minimal checkout UI. No build step, no framework — served
// as-is by nginx behind the gateway (same origin, session-cookie auth
// via the gateway's existing OIDC login, ADR-0005's BFF pattern). Every
// fetch() below is same-origin: if the session ever isn't valid, the
// gateway's own SecurityConfig 302s to Keycloak login before any
// response reaches this script, so there is no separate "logged out"
// state to handle here.

const cart = []; // [{ productId, name, unitPrice, quantity }]
let me = null;
let forceTrace = false; // ADR-0032: CAN_TRACE-gated Settings toggle

// ADR-0023: correlation ID and trace ID are distinct, with distinct
// uniqueness guarantees. correlationId is fresh per individual API call
// and honored by CorrelationTraceFilter on servlet-based backends if
// present on the incoming request rather than generated fresh, so
// setting it here is what makes it consistent downstream.
//
// ADR-0052 (2026-08-17): this UI no longer sends X-Trace-Id at all. The
// gateway now always generates its own X-Trace-Id server-side
// (CorrelationTraceGatewayFilter) and never honors a client-supplied
// value — a deliberate security fix, since a client-controlled trace ID
// let a caller inject arbitrary values into the server's own log
// correlation. A client-generated TRACE_ID sent here would therefore be
// silently discarded by the gateway on every request — pure dead weight
// that only invited confusion (comparing this UI's own value against
// what actually shows up in Loki/Tempo never matched). Removed rather
// than left in place unused.
function apiFetch(url, options = {}) {
  return fetch(url, {
    ...options,
    headers: {
      ...options.headers,
      'X-Correlation-Id': crypto.randomUUID(),
      // ADR-0032: only sent when the CAN_TRACE-gated toggle is on —
      // absent (not "false") otherwise, so a session without the role
      // never even has the option to influence backend sampling.
      ...(forceTrace ? { 'X-Force-Trace': 'true' } : {}),
    },
  });
}

// Every response — success or failure — carries these two headers back
// (CorrelationTraceFilter for servlet-based backends, the gateway's own
// CorrelationTraceGatewayFilter for anything the gateway answers itself,
// e.g. a circuit-breaker fallback). Pulling them out here is what lets a
// user-visible error message double as a ready-to-paste Loki query term
// instead of a dead end.
function refFrom(res) {
  return res.headers.get('X-Correlation-Id') || 'unknown';
}

// The authoritative cross-saga correlation ID (appTraceId internally —
// see CorrelationConstants.MDC_TRACE_ID_KEY's Javadoc). This response
// header IS that value: CorrelationTraceFilter puts the same string into
// both the response's X-Trace-Id header and MDC's appTraceId key for
// every servlet backend, and OrderCorrelationScope carries it unchanged
// through every Kafka/RabbitMQ hop of the saga afterward. Deliberately
// distinct from X-Correlation-Id (refFrom above) — that one is a
// per-call idempotency-style ref; this one is the single ID genuinely
// consistent end-to-end across the whole order lifecycle, and the only
// one worth showing a customer-support-facing "search by this" value.
function traceIdFrom(res) {
  return res.headers.get('X-Trace-Id') || 'unknown';
}

// Full value only, deliberately never truncated. A previous version of
// this UI showed a shortened prefix ("trace ref: e58e539c") that looked
// like a useful reference but couldn't actually be pasted into a Tempo
// or Loki search (both need an exact match) — found live, reported as
// "not useful information to search in Grafana." A full, copyable value
// with a clear label is the fix; the dashboard link below tells the
// reader exactly where to paste it.
function renderSagaIdNotice(container, orderId, traceId) {
  container.textContent = '';
  const line1 = document.createElement('p');
  line1.textContent = `Order #${orderId} placed — payment processing.`;
  container.appendChild(line1);

  const line2 = document.createElement('p');
  const label = document.createElement('strong');
  label.textContent = 'Saga ID (Trace ID): ';
  line2.appendChild(label);
  const code = document.createElement('code');
  code.textContent = traceId;
  line2.appendChild(code);

  if (traceId !== 'unknown' && navigator.clipboard) {
    const copyBtn = document.createElement('button');
    copyBtn.type = 'button';
    copyBtn.textContent = 'Copy';
    copyBtn.style.marginLeft = '0.5em';
    copyBtn.addEventListener('click', () => {
      navigator.clipboard.writeText(traceId).then(() => {
        copyBtn.textContent = 'Copied!';
        setTimeout(() => { copyBtn.textContent = 'Copy'; }, 1500);
      });
    });
    line2.appendChild(copyBtn);
  }
  container.appendChild(line2);

  const line3 = document.createElement('p');
  line3.textContent = 'Search this in Grafana → Order Trace Explorer dashboard to see the full request history.';
  container.appendChild(line3);
}

async function loadMe() {
  const res = await apiFetch('/user/me');
  if (!res.ok) throw new Error(`GET /user/me -> ${res.status}`);
  me = await res.json();
  document.getElementById('whoami').textContent = me.name || me.email;
  document.getElementById('roles-menu-list').innerHTML =
    me.roles.map((r) => `<li>${r}</li>`).join('');

  // Role-gated tabs — hidden by default in index.html, unhidden here.
  // Client-side only, for UI convenience: the real enforcement is the
  // gateway's RequireRole filter plus catalog-service/inventory-service's
  // own @PreAuthorize + ownership checks, same split of responsibility
  // as everywhere else in this app.
  if (me.roles.includes('PROVIDER')) {
    document.getElementById('tab-btn-provider').hidden = false;
    loadMyProducts();
  }
  // ADR-0033: INVENTORY_ADMIN (or PLATFORM_ADMIN, which carries it via
  // Keycloak composite expansion) — not the old, now-inert ADMIN, which
  // no longer grants any operational capability.
  if (me.roles.includes('INVENTORY_ADMIN') || me.roles.includes('PLATFORM_ADMIN')) {
    document.getElementById('tab-btn-admin').hidden = false;
  }
  // ADR-0032: force-trace Settings toggle.
  if (me.roles.includes('CAN_TRACE')) {
    document.getElementById('settings-menu-trigger').hidden = false;
  }
  await loadMyOrders();
}

function switchTab(name) {
  for (const btn of document.querySelectorAll('.tab-btn')) {
    btn.classList.toggle('active', btn.dataset.tab === name);
  }
  for (const panel of document.querySelectorAll('.tab-panel')) {
    panel.classList.toggle('active', panel.id === `tab-${name}`);
  }
}

// ADR-0031: status-only order history, refreshed on login and after
// every successful checkout — no cancel/return actions, no line items.
// Own tab + pagination (page/size, same pattern as the product catalog).
const ORDERS_PAGE_SIZE = 10;
let currentOrdersPage = 0;

async function loadMyOrders(page = 0) {
  currentOrdersPage = page;
  const res = await apiFetch(`/order/customer/${me.id}?page=${page}&size=${ORDERS_PAGE_SIZE}`);
  if (!res.ok) return; // non-fatal: an empty/failed order list shouldn't block the rest of the page
  const result = await res.json();
  const list = document.getElementById('order-list');
  list.innerHTML = '';
  for (const o of result.items) {
    const li = document.createElement('li');
    const when = new Date(o.orderCreatedOn).toLocaleString();
    li.textContent = `Order #${o.id} — $${o.totalAmount.toFixed(2)} — ${o.orderStatus} / ${o.paymentStatus} — ${when}`;
    list.appendChild(li);
  }
  renderOrdersPager(result);
}

function renderOrdersPager(result) {
  const pager = document.getElementById('orders-pager');
  pager.innerHTML = '';
  if (result.totalElements === 0) {
    pager.textContent = 'No orders yet.';
    return;
  }
  const isFirst = result.page <= 0;
  const isLast = result.page >= result.totalPages - 1;
  const prevBtn = document.createElement('button');
  prevBtn.textContent = 'Prev';
  prevBtn.disabled = isFirst;
  prevBtn.onclick = () => loadMyOrders(currentOrdersPage - 1);
  const nextBtn = document.createElement('button');
  nextBtn.textContent = 'Next';
  nextBtn.disabled = isLast;
  nextBtn.onclick = () => loadMyOrders(currentOrdersPage + 1);
  const label = document.createElement('span');
  label.textContent = ` Page ${result.page + 1} of ${result.totalPages} (${result.totalElements} orders) `;
  pager.append(prevBtn, label, nextBtn);
}

async function loadMyProducts() {
  const res = await apiFetch('/catalog/products/mine');
  if (!res.ok) throw new Error(`GET /catalog/products/mine -> ${res.status}`);
  const products = await res.json();
  const list = document.getElementById('provider-products');
  list.innerHTML = '';
  for (const p of products) {
    const li = document.createElement('li');
    const label = document.createElement('span');
    label.textContent = `${p.name} — $${p.price.toFixed(2)} [${p.status}]${p.active ? '' : ' (inactive)'}`;
    li.appendChild(label);
    // ADR-0030: DRAFT products are invisible to browse/search until the
    // owning provider (or an admin) explicitly publishes them.
    if (p.status === 'DRAFT') {
      const publishBtn = document.createElement('button');
      publishBtn.textContent = 'Publish';
      publishBtn.onclick = () => publishProduct(p.id);
      li.appendChild(publishBtn);
    }
    list.appendChild(li);
  }
}

async function publishProduct(id) {
  const res = await apiFetch(`/catalog/products/${id}/publish`, { method: 'PUT' });
  if (!res.ok) throw new Error(`PUT /catalog/products/${id}/publish -> ${res.status} (ref: ${refFrom(res)})`);
  await loadMyProducts();
}

async function createProviderProduct(event) {
  event.preventDefault();
  const status = document.getElementById('provider-status');
  status.textContent = 'Adding…';
  try {
    const res = await apiFetch('/catalog/products', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: document.getElementById('p-name').value,
        description: document.getElementById('p-description').value,
        price: parseFloat(document.getElementById('p-price').value),
        quantity: parseInt(document.getElementById('p-quantity').value, 10),
      }),
    });
    const ref = refFrom(res);
    if (!res.ok) throw new Error(`POST /catalog/products -> ${res.status} (ref: ${ref})`);
    document.getElementById('provider-form').reset();
    status.textContent = `Added (ref: ${ref}).`;
    await loadMyProducts();
  } catch (err) {
    status.textContent = `Failed: ${err.message}`;
  }
}

async function restock(event) {
  event.preventDefault();
  const status = document.getElementById('restock-status');
  status.textContent = 'Adding stock…';
  try {
    const res = await apiFetch('/inventory/add', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        items: [{
          productId: parseInt(document.getElementById('r-product-id').value, 10),
          quantity: parseInt(document.getElementById('r-quantity').value, 10),
        }],
      }),
    });
    const ref = refFrom(res);
    if (!res.ok) throw new Error(`POST /inventory/add -> ${res.status} (ref: ${ref})`);
    document.getElementById('restock-form').reset();
    status.textContent = `Stock added (ref: ${ref}).`;
  } catch (err) {
    status.textContent = `Failed: ${err.message}`;
  }
}

const PAGE_SIZE = 10;
let currentQuery = '';
let currentPage = 0;

async function loadProducts(query = currentQuery, page = 0) {
  currentQuery = query;
  currentPage = page;
  const res = await apiFetch(`/catalog/products/search?query=${encodeURIComponent(query)}&page=${page}&size=${PAGE_SIZE}`);
  if (!res.ok) throw new Error(`GET /catalog/products/search -> ${res.status}`);
  const result = await res.json();
  const list = document.getElementById('products');
  list.innerHTML = '';
  for (const p of result.items) {
    const li = document.createElement('li');
    const addBtn = document.createElement('button');
    addBtn.textContent = 'Add';
    addBtn.onclick = () => addToCart(p);
    li.innerHTML = `<span>${p.name} — $${p.price.toFixed(2)} <em>(sold by ${p.providerName || 'Unknown'})</em></span>`;
    li.appendChild(addBtn);
    list.appendChild(li);
  }
  renderPager(result);
}

function renderPager(result) {
  const pager = document.getElementById('products-pager');
  const isFirst = result.page <= 0;
  const isLast = result.page >= result.totalPages - 1;
  pager.innerHTML = '';
  if (result.totalElements === 0) {
    pager.textContent = 'No products found.';
    return;
  }
  const prevBtn = document.createElement('button');
  prevBtn.textContent = 'Prev';
  prevBtn.disabled = isFirst;
  prevBtn.onclick = () => loadProducts(currentQuery, currentPage - 1);
  const nextBtn = document.createElement('button');
  nextBtn.textContent = 'Next';
  nextBtn.disabled = isLast;
  nextBtn.onclick = () => loadProducts(currentQuery, currentPage + 1);
  const label = document.createElement('span');
  label.textContent = ` Page ${result.page + 1} of ${result.totalPages} (${result.totalElements} products) `;
  pager.append(prevBtn, label, nextBtn);
}

function addToCart(product) {
  const existing = cart.find((i) => i.productId === product.id);
  if (existing) {
    existing.quantity += 1;
  } else {
    cart.push({ productId: product.id, name: product.name, unitPrice: product.price, quantity: 1 });
  }
  renderCart();
}

function renderCart() {
  const list = document.getElementById('cart-items');
  list.innerHTML = '';
  let total = 0;
  for (const item of cart) {
    total += item.unitPrice * item.quantity;
    const li = document.createElement('li');
    li.textContent = `${item.name} x${item.quantity} — $${(item.unitPrice * item.quantity).toFixed(2)}`;
    list.appendChild(li);
  }
  document.getElementById('cart-total').textContent = total.toFixed(2);
  document.getElementById('checkout').disabled = cart.length === 0;
}

async function checkout() {
  const status = document.getElementById('checkout-status');
  status.textContent = 'Placing order…';
  try {
    const res = await apiFetch('/order', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // A fresh key per checkout click — order-service's
        // IdempotencyService treats the same key as "already handled"
        // and replays the first response instead of creating a second
        // order, so an accidental double-click (or the browser retrying
        // a request it thinks failed) can't double-charge a cart.
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        customerId: me.id,
        items: cart.map((i) => ({ productId: i.productId, quantity: i.quantity, unitPrice: i.unitPrice })),
      }),
    });
    const ref = refFrom(res);
    if (!res.ok) throw new Error(`POST /order -> ${res.status} (ref: ${ref})`);
    const order = await res.json();
    // Order #<id> is the real, short, database-unique identifier
    // (auto-increment primary key) — the one to quote for "my order."
    // The saga/trace ID (traceIdFrom above) is the one worth showing in
    // full for support/search purposes — see renderSagaIdNotice's
    // Javadoc for why a truncated ref was actively unhelpful here.
    renderSagaIdNotice(status, order.id, traceIdFrom(res));
    cart.length = 0;
    renderCart();
    await loadMyOrders();
  } catch (err) {
    status.textContent = `Checkout failed: ${err.message}`;
  }
}

document.getElementById('search').addEventListener('input', (e) => loadProducts(e.target.value, 0));
document.getElementById('checkout').addEventListener('click', checkout);
document.getElementById('provider-form').addEventListener('submit', createProviderProduct);
document.getElementById('restock-form').addEventListener('submit', restock);
for (const btn of document.querySelectorAll('.tab-btn')) {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab));
}

// Single username dropdown: Roles / Settings (CAN_TRACE only) / Logout —
// click the username to open/close it; click anywhere outside to close.
// Roles/Settings are submenus revealed on hover/click of their own row
// (see style.css), not separate top-level buttons — consolidates what
// used to be a flat button+dropdown+panel spread across the header into
// one place, per the "Username -> Roles/Logout/Settings" structure.
const userMenuBtn = document.getElementById('user-menu-btn');
const userMenuDropdown = document.getElementById('user-menu-dropdown');
userMenuBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  const isOpen = !userMenuDropdown.hidden;
  userMenuDropdown.hidden = isOpen;
  userMenuBtn.setAttribute('aria-expanded', String(!isOpen));
});
document.addEventListener('click', (e) => {
  if (!userMenuDropdown.hidden && !document.getElementById('user-menu').contains(e.target)) {
    userMenuDropdown.hidden = true;
    userMenuBtn.setAttribute('aria-expanded', 'false');
  }
});
// ADR-0032: only reachable at all for a session with CAN_TRACE
// (settings-menu-trigger stays hidden otherwise — see loadMe()).
document.getElementById('force-trace-toggle').addEventListener('change', (e) => {
  forceTrace = e.target.checked;
});

loadMe().then(() => loadProducts()).catch((err) => {
  document.getElementById('whoami').textContent = `Error: ${err.message}`;
});
