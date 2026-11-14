// Cart totals. Prices are integers in cents.

export function cartTotal(items) {
  let total = 0;
  for (const item of items) {
    total += item.price * item.quantity;
  }
  return total;
}

export function formatPrice(cents) {
  return (cents / 100).toFixed(2) + ' €';
}
