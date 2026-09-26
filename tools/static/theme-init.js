/* Run before rendering: validate old preferences and set native control colors. */
(() => {
  const ids = ['midnight', 'aurora', 'ember', 'neon', 'sakura', 'paper'];
  let theme = 'midnight';
  try { theme = localStorage.getItem('sgm-theme') || theme; } catch (_) {}
  if (!ids.includes(theme)) theme = 'midnight';
  document.documentElement.dataset.theme = theme;
  document.querySelector('meta[name="color-scheme"]').content =
    ['sakura', 'paper'].includes(theme) ? 'light' : 'dark';
})();
