const themeToggle = document.getElementById('theme-toggle');
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)');
let selectedTheme = null;
try {
  selectedTheme = localStorage.getItem('cloudai-site-theme');
} catch {
  // The page also works when browser storage is unavailable.
}
if (selectedTheme === 'light' || selectedTheme === 'dark') {
  document.documentElement.dataset.theme = selectedTheme;
}
function updateThemeLabel() {
  const dark = document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === 'dark'
    : systemTheme.matches;
  themeToggle.textContent = dark ? '浅色' : '深色';
  themeToggle.setAttribute('aria-label', dark ? '切换为浅色主题' : '切换为深色主题');
}
updateThemeLabel();
themeToggle.hidden = false;
systemTheme.addEventListener('change', updateThemeLabel);
themeToggle.addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === 'dark'
    : systemTheme.matches;
  const theme = dark ? 'light' : 'dark';
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem('cloudai-site-theme', theme);
  } catch {
    // Keep the selected theme for this page even without persistent storage.
  }
  updateThemeLabel();
});

if (navigator.clipboard && window.isSecureContext) {
  document.querySelectorAll('[data-copy]').forEach((button) => {
    button.hidden = false;
    button.addEventListener('click', async () => {
      const command = document.getElementById(button.dataset.copy).textContent;
      const status = document.getElementById('copy-status');
      try {
        await navigator.clipboard.writeText(command);
        button.textContent = '已复制';
        status.textContent = '命令已复制到剪贴板。';
      } catch {
        button.textContent = '请手动复制';
        status.textContent = '浏览器未允许复制，请选择代码并手动复制。';
      }
      window.setTimeout(() => { button.textContent = '复制命令'; }, 2500);
    });
  });
}
