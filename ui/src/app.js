// Phase 8 minimal checkout UI. No build step, no framework — served
// as-is by nginx behind the gateway (same origin, session-cookie auth
// via the gateway's existing OIDC login, ADR-0005's BFF pattern). Every
// fetch() below is same-origin: if the session ever isn't valid, the
// gateway's own SecurityConfig 302s to Keycloak login before any
// response reaches this script, so there is no separate "logged out"
// state to handle here.

const cart = []; // [{ productId, name, unitPrice, quantity }]
let me = null;

// ADR-0023: correlation ID and trace ID are distinct, with distinct
// uniqueness guarantees. This UI is the true entry point for end-to-end
// tracing (the gateway is reactive/WebFlux and doesn't run
// CorrelationTraceFilter, which only auto-registers into servlet-based
// backend services) — without the browser setting these, each backend
// service independently generated its own random one per request,
// making a single user action impossible to trace across hops. traceId
// is fixed for this whole page session (one browser tab = one trace);
// correlationId is fresh per individual API call. Both are honored by
// CorrelationTraceFilter if present on the incoming request rather than
// generated fresh, so setting them here is what actually makes them
// consistent downstream.
const TRACE_ID = crypto.randomUUID();

function apiFetch(url, options = {}) {
  return fetch(url, {
    ...options,
    headers: {
      ...options.headers,
      'X-Correlation-Id': crypto.randomUUID(),
      'X-Trace-Id': TRACE_ID,
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

async function loadMe() {
  const res = await apiFetch('/user/me');
  if (!res.ok) throw new Error(`GET /user/me -> ${res.status}`);
  me = await res.json();
  document.getElementById('whoami').textContent = `${me.name || me.email} (${me.roles.join(', ')})`;

  // Role-gated tabs — hidden by default in index.html, unhidden here.
  // Client-side only, for UI convenience: the real enforcement is the
  // gateway's RequireRole filter plus catalog-service/inventory-service's
  // own @PreAuthorize + ownership checks, same split of responsibility
  // as everywhere else in this app.
  if (me.roles.includes('PROVIDER')) {
    document.getElementById('tab-btn-provider').hidden = false;
    loadMyProducts();
  }
  if (me.roles.includes('ADMIN')) {
    document.getElementById('tab-btn-admin').hidden = false;
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
async function loadMyOrders() {
  const res = await apiFetch(`/order/customer/${me.id}`);
  if (!res.ok) return; // non-fatal: an empty/failed order list shouldn't block the rest of the page
  const orders = await res.json();
  const list = document.getElementById('order-list');
  list.innerHTML = '';
  for (const o of orders.sort((a, b) => b.id - a.id)) {
    const li = document.createElement('li');
    const when = new Date(o.orderCreatedOn).toLocaleString();
    li.textContent = `Order #${o.id} — $${o.totalAmount.toFixed(2)} — ${o.orderStatus} / ${o.paymentStatus} — ${when}`;
    list.appendChild(li);
  }
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
    status.textContent = `Order #${order.id} placed (ref: ${ref}) — payment processing.`;
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

loadMe().then(() => loadProducts()).catch((err) => {
  document.getElementById('whoami').textContent = `Error: ${err.message}`;
});
