// Phase 8 minimal checkout UI. No build step, no framework — served
// as-is by nginx behind the gateway (same origin, session-cookie auth
// via the gateway's existing OIDC login, ADR-0005's BFF pattern). Every
// fetch() below is same-origin: if the session ever isn't valid, the
// gateway's own SecurityConfig 302s to Keycloak login before any
// response reaches this script, so there is no separate "logged out"
// state to handle here.

const cart = []; // [{ productId, name, unitPrice, quantity }]
let me = null;

async function loadMe() {
  const res = await fetch('/user/me');
  if (!res.ok) throw new Error(`GET /user/me -> ${res.status}`);
  me = await res.json();
  document.getElementById('whoami').textContent = `${me.name || me.email} (${me.roles.join(', ')})`;
}

async function loadProducts(query = '') {
  const res = await fetch(`/catalog/products/search?query=${encodeURIComponent(query)}`);
  if (!res.ok) throw new Error(`GET /catalog/products/search -> ${res.status}`);
  const products = await res.json();
  const list = document.getElementById('products');
  list.innerHTML = '';
  for (const p of products) {
    const li = document.createElement('li');
    const addBtn = document.createElement('button');
    addBtn.textContent = 'Add';
    addBtn.onclick = () => addToCart(p);
    li.innerHTML = `<span>${p.name} — $${p.price.toFixed(2)}</span>`;
    li.appendChild(addBtn);
    list.appendChild(li);
  }
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
    const res = await fetch('/order', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        customerId: me.id,
        items: cart.map((i) => ({ productId: i.productId, quantity: i.quantity, unitPrice: i.unitPrice })),
      }),
    });
    if (!res.ok) throw new Error(`POST /order -> ${res.status}`);
    const order = await res.json();
    status.textContent = `Order #${order.id} placed — payment processing.`;
    cart.length = 0;
    renderCart();
  } catch (err) {
    status.textContent = `Checkout failed: ${err.message}`;
  }
}

document.getElementById('search').addEventListener('input', (e) => loadProducts(e.target.value));
document.getElementById('checkout').addEventListener('click', checkout);

loadMe().then(() => loadProducts()).catch((err) => {
  document.getElementById('whoami').textContent = `Error: ${err.message}`;
});
